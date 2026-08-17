/*
 * zve_sensor.c — ZhangVirtualEnv 系统级传感器全局模拟（Native 层）
 *
 * Hook 点：android::SensorEventQueue::write（libsensor.so，导出符号）
 *   SensorEventQueue::write(const sp<BitTube>&, const ASensorEvent*, size_t)
 *   @ +0: bti c ; @ +4: mov w3,#0x68 ; @ +8: b <helper>
 * 仅改写 +8 的 4 字节分支指令，重定向到本库重写桩；桩保存 x0-x3+LR，
 * 调用 C 重写后尾跳原 helper。无 prologue 搬迁，失败即恢复，fail-open。
 *
 * 运动数据：1:1 移植 Kotlin VirtualMotionEngine（四相步态 + 手机姿态），
 * 所有传感器来自同一运动模型，禁止固定 step_counter++ / 纯随机。
 *
 * 约束：只允许在 system_server 进程加载执行（Kotlin 侧门禁）。
 */

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
#include <time.h>
#include <math.h>
#include <android/log.h>

#define LOG_TAG "ZVirtualEnv-NativeSensor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/* ---------- 传感器事件布局（arm64，104 字节，与 AOSP sensors.h 一致） ---------- */

typedef struct {
    int32_t version;    /* +0x00 */
    int32_t sensor;     /* +0x04 handle */
    int32_t type;       /* +0x08 */
    int32_t reserved0;  /* +0x0c */
    int64_t timestamp;  /* +0x10 */
    union {
        float data[16];   /* +0x18 */
        uint64_t data64;  /* +0x18 step_counter */
    };
    uint32_t flags;     /* +0x58 */
    int32_t reserved1[3];
} zve_sensor_event_t;

#define TYPE_ACCELEROMETER 1
#define TYPE_MAGNETIC_FIELD 2
#define TYPE_GYROSCOPE 4
#define TYPE_GRAVITY 9
#define TYPE_LINEAR_ACCELERATION 10
#define TYPE_STEP_DETECTOR 18
#define TYPE_STEP_COUNTER 19

/* ---------- 配置与运动状态 ---------- */

typedef struct {
    int enabled;
    int mode;               /* 0 stationary / 1 walk / 2 run */
    float step_frequency;   /* steps/min */
    float speed_kmh;        /* <=0 由步长模型推导 */
    float amplitude;        /* <0 用模式默认 */
    int random_noise;
    float heading_deg;      /* 预留：GPS 联动 */
} zve_config_t;

typedef struct {
    double step_phase;      /* 0..1 全局步态相位 */
    uint64_t step_count;    /* 累计步数 */
    int steps_pending;      /* 待消费的 step detector 计数 */
    double distance_m;
    double t_sec;           /* 引擎时间（monotonic 累计） */
    int64_t last_tick_ns;   /* 上次推进时间基准 */
    int activity;           /* 当前模式副本 */
    float step_freq_eff;
    double speed_kmh_eff;
    float amp_eff;
    double pitch_deg;
    double roll_deg;
} zve_motion_t;

typedef struct {
    volatile int hooked;
    volatile int last_error;
    volatile uint64_t events_rewritten;
    volatile int delivery_verified; /* 启用期间确实改写过事件（供 App 侧抑制 LEGACY） */
    volatile uint64_t rewrite_calls; /* rewrite 入口调用次数（enabled 检查前） */
    volatile int last_type;          /* 循环内最后一个事件 type（诊断） */
    volatile uint64_t inject_count;  /* 主动注入 STEP_COUNTER 事件次数（计步器稳定） */
} zve_stats_t;

static zve_config_t g_cfg;
static zve_motion_t g_motion;
static zve_stats_t g_stats;

/* 多 hook（sendEventsToAllClients + write + sendObjects）可能先后处理同一事件批，
 * 用批首事件 timestamp 幂等去重（count 不参与：主动注入会改变 count）。 */
static uint64_t g_last_batch_ts;
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

/* Hook 状态 */
static void *g_write_addr;
static void *g_write_helper_addr;
static uint32_t g_orig_b_insn; /* write+8 原始指令 */
static void *g_stub_mem;       /* 手工构建的 arm64 重写桩页 */

/* sendEvents 入口 hook 状态（g_stats.hooked=2 时生效，当前未启用） */
static void *g_se_addr;
static uint32_t g_se_orig_insn; /* sendEvents 入口原始指令（paciasp） */
static void *g_se_stub_mem;

/* BitTube::sendObjects 入口 hook 状态（g_stats.hooked=3 时生效） */
static void *g_so_addr;
static uint32_t g_so_orig_insn; /* sendObjects 入口原始指令（paciasp） */
static void *g_so_stub_mem;

/* SensorService::sendEventsToAllClients 入口 hook 状态（g_stats.hooked=4 时生效） */
static void *g_batch_addr;
static uint32_t g_batch_orig_insn; /* 入口原始指令（paciasp） */
static void *g_batch_stub_mem;

/* STEP_COUNTER handle：Java 传入优先，真实事件学习兜底 */
static volatile int g_step_handle;
static volatile int64_t g_last_inject_ns;

static uint64_t g_rng_state = 0x9E3779B97F4A7C15ULL;

/* ---------- 基础工具 ---------- */

static double zve_noise(double scale) {
    uint64_t x = g_rng_state;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    g_rng_state = x;
    double u = (double)(x >> 11) / (double)(1ULL << 53);
    return (u * 2.0 - 1.0) * scale;
}

static double zve_gait(double phase) {
    double p = fmod(phase, 1.0);
    if (p < 0.0) p += 1.0;
    if (p < 0.15) return 0.5 - 0.5 * cos(M_PI * p / 0.15);
    if (p < 0.35) return 1.0;
    if (p < 0.70) return 0.5 + 0.5 * cos(M_PI * (p - 0.35) / 0.35);
    double q = (p - 0.70) / 0.30;
    return 0.0 - 0.2 * sin(M_PI * q);
}

static double zve_speed_for(int steps) {
    if (steps <= 0) return 0.0;
    double stride;
    if (steps < 150) stride = 0.45 + (steps - 60) / 60.0 * 0.25;
    else stride = 0.85 + (steps - 150) / 70.0 * 0.35;
    return stride * steps * 60.0 / 1000.0;
}

static float zve_amp_for(int activity, int steps) {
    switch (activity) {
        case 0: return 0.05f;
        case 2: return 3.0f + (8.0f - 3.0f) * ((steps - 150) / 70.0f);
        default: return 1.0f + (3.0f - 1.0f) * ((steps - 60) / 60.0f);
    }
}

static void zve_motion_apply_profile(void) {
    int activity = g_cfg.mode;
    if (activity < 0) activity = 0;
    if (activity > 2) activity = 2;
    float steps = g_cfg.step_frequency;
    if (steps < 0) steps = 0;
    if (activity == 0) steps = 0;
    g_motion.activity = activity;
    g_motion.step_freq_eff = steps;

    double speed = g_cfg.speed_kmh;
    if (speed <= 0) speed = zve_speed_for((int)steps);
    g_motion.speed_kmh_eff = speed;

    float amp = g_cfg.amplitude;
    if (amp < 0) amp = zve_amp_for(activity, (int)steps);
    g_motion.amp_eff = amp;
}

static double zve_step_meters(void) {
    if (g_motion.step_freq_eff <= 0) return 0.0;
    double speed_ms = g_motion.speed_kmh_eff * 1000.0 / 3600.0;
    return speed_ms / (g_motion.step_freq_eff / 60.0);
}

/* 推进相位/步数；在 g_mutex 内调用 */
static void zve_motion_advance(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t now_ns = (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
    double dt_sec = 0.0;
    if (g_motion.last_tick_ns == 0) {
        g_motion.last_tick_ns = now_ns;
    } else {
        dt_sec = (double)(now_ns - g_motion.last_tick_ns) / 1e9;
        if (dt_sec < 0) dt_sec = 0;
    }
    g_motion.last_tick_ns = now_ns;
    g_motion.t_sec += dt_sec;
    if (g_motion.step_freq_eff <= 0) {
        g_motion.step_phase = 0.0;
        return;
    }
    double step_hz = g_motion.step_freq_eff / 60.0;
    g_motion.step_phase += step_hz * dt_sec;
    while (g_motion.step_phase >= 1.0) {
        g_motion.step_phase -= 1.0;
        g_motion.step_count++;
        g_motion.distance_m += zve_step_meters();
        g_motion.steps_pending++;
    }
}

static void zve_gravity(float out[3]) {
    double p = g_motion.pitch_deg * M_PI / 180.0;
    double r = g_motion.roll_deg * M_PI / 180.0;
    out[0] = (float)(9.81 * sin(r));
    out[1] = (float)(-9.81 * sin(p));
    out[2] = (float)(9.81 * cos(p) * cos(r));
}

static void zve_gait_dir(float out[3]) {
    double p = g_motion.pitch_deg * M_PI / 180.0;
    double r = g_motion.roll_deg * M_PI / 180.0;
    out[0] = (float)(-sin(r));
    out[1] = (float)(sin(p));
    out[2] = (float)(-cos(p) * cos(r));
}

static void zve_phone_update(void) {
    double t = g_motion.t_sec;
    double swing_amp = g_motion.activity == 2 ? 14.0 : (g_motion.activity == 0 ? 0.5 : 7.0);
    double roll_amp = g_motion.activity == 2 ? 6.0 : (g_motion.activity == 0 ? 0.5 : 3.0);
    double freq = g_motion.step_freq_eff > 0 ? g_motion.step_freq_eff / 60.0 : 0.0;
    g_motion.pitch_deg = 5.0
        + 6.0 * sin(2.0 * M_PI * 0.13 * t)
        + 3.0 * sin(2.0 * M_PI * 0.07 * t)
        + swing_amp * sin(2.0 * M_PI * freq * t);
    g_motion.roll_deg = 0.0
        + 3.0 * sin(2.0 * M_PI * 0.11 * t + 0.7)
        + roll_amp * sin(2.0 * M_PI * freq * t + 1.3);
}

/* ---------- 传感器生成器（全部从同一运动状态取数） ---------- */

static void zve_gen_accel(zve_sensor_event_t *ev) {
    float g[3], dir[3];
    zve_gravity(g);
    zve_gait_dir(dir);
    float amp = g_motion.amp_eff;
    float ns = g_cfg.random_noise ? 0.05f : 0.0f;
    float vertical = (float)(amp * (zve_gait(g_motion.step_phase) * 2.0 - 1.0));
    float horiz = (float)(amp * 0.2 * sin(2.0 * M_PI * g_motion.step_phase));
    ev->data[0] = g[0] + dir[0] * vertical + horiz + (float)zve_noise(ns);
    ev->data[1] = g[1] + dir[1] * vertical + horiz * 0.5f + (float)zve_noise(ns);
    ev->data[2] = g[2] + dir[2] * vertical + (float)zve_noise(ns);
}

static void zve_gen_linear_accel(zve_sensor_event_t *ev) {
    float accel[3] = {ev->data[0], ev->data[1], ev->data[2]};
    float g[3];
    zve_gravity(g);
    ev->data[0] = accel[0] - g[0];
    ev->data[1] = accel[1] - g[1];
    ev->data[2] = accel[2] - g[2];
}

static void zve_gen_gravity(zve_sensor_event_t *ev) {
    float g[3];
    zve_gravity(g);
    float ns = g_cfg.random_noise ? 0.01f : 0.0f;
    ev->data[0] = g[0] + (float)zve_noise(ns);
    ev->data[1] = g[1] + (float)zve_noise(ns);
    ev->data[2] = g[2] + (float)zve_noise(ns);
}

static void zve_gen_gyro(zve_sensor_event_t *ev) {
    double t = g_motion.t_sec;
    double drift_p = 0.02 * sin(2.0 * M_PI * 0.13 * t);
    double drift_r = 0.015 * sin(2.0 * M_PI * 0.11 * t + 0.7);
    double swing = g_motion.activity == 2 ? 0.9 : (g_motion.activity == 0 ? 0.02 : 0.45);
    double freq = g_motion.step_freq_eff > 0 ? g_motion.step_freq_eff / 60.0 : 0.0;
    float pitch_rate = (float)(drift_p + swing * cos(2.0 * M_PI * freq * t));
    float roll_rate = (float)(drift_r + swing * 0.6 * cos(2.0 * M_PI * freq * t + 1.3));
    float yaw_rate = (float)(0.01 * sin(2.0 * M_PI * 0.09 * t));
    if (g_cfg.random_noise) {
        pitch_rate += (float)zve_noise(0.01);
        roll_rate += (float)zve_noise(0.01);
        yaw_rate += (float)zve_noise(0.005);
    }
    ev->data[0] = pitch_rate;
    ev->data[1] = roll_rate;
    ev->data[2] = yaw_rate;
}

static void zve_gen_magnetic(zve_sensor_event_t *ev) {
    ev->data[0] = 35.0f + (float)zve_noise(g_cfg.random_noise ? 0.5f : 0.0f);
    ev->data[1] = -12.0f + (float)zve_noise(g_cfg.random_noise ? 0.5f : 0.0f);
    ev->data[2] = 48.0f + (float)zve_noise(g_cfg.random_noise ? 0.5f : 0.0f);
}

/* ---------- 事件重写（hook 桩调用；临界区内无日志/无分配） ---------- */

__attribute__((used, noinline))
void zve_rewrite_events(const zve_sensor_event_t *events, size_t count) {
    g_stats.rewrite_calls++;
    if (events == NULL || count == 0 || !g_cfg.enabled) return;
    pthread_mutex_lock(&g_mutex);

    /* 幂等：同一事件批（首事件 timestamp）已被另一 hook 改写则跳过，
     * 避免 sendEventsToAllClients / write / sendObjects 三 hook 时重复推进。
     * 只比较 timestamp：主动注入会追加事件改变 count，(ts,count) 指纹会误判。
     * timestamp==0 的批（meta/flush 等）不做指纹去重，避免误跳真实事件。 */
    uint64_t batch_ts = events[0].timestamp;
    if (batch_ts != 0 && batch_ts == g_last_batch_ts) {
        pthread_mutex_unlock(&g_mutex);
        return;
    }
    g_last_batch_ts = batch_ts;

    zve_motion_advance();
    zve_phone_update();

    size_t rewritten = 0;
    for (size_t i = 0; i < count; i++) {
        zve_sensor_event_t *ev = (zve_sensor_event_t *)&events[i];
        g_stats.last_type = ev->type;
        switch (ev->type) {
            case TYPE_STEP_COUNTER:
                if (g_step_handle == 0) g_step_handle = ev->sensor; /* 学习真实 handle */
                ev->data64 = g_motion.step_count;
                rewritten++;
                break;
            case TYPE_STEP_DETECTOR:
                if (g_motion.steps_pending > 0) {
                    ev->data[0] = 1.0f;
                    g_motion.steps_pending--;
                    rewritten++;
                }
                break;
            case TYPE_ACCELEROMETER:
                zve_gen_accel(ev);
                rewritten++;
                break;
            case TYPE_LINEAR_ACCELERATION:
                zve_gen_accel(ev); /* accel 值临时写入，再由 linear 差值 */
                zve_gen_linear_accel(ev);
                rewritten++;
                break;
            case TYPE_GRAVITY:
                zve_gen_gravity(ev);
                rewritten++;
                break;
            case TYPE_GYROSCOPE:
                zve_gen_gyro(ev);
                rewritten++;
                break;
            case TYPE_MAGNETIC_FIELD:
                zve_gen_magnetic(ev);
                rewritten++;
                break;
            default:
                break; /* meta_data / 其它类型原样透传 */
        }
    }
    if (rewritten > 0) {
        g_stats.events_rewritten += rewritten;
        g_stats.delivery_verified = 1;
    }
    pthread_mutex_unlock(&g_mutex);
}

/*
 * sendEventsToAllClients 入口处理（zve_process_batch）：
 * 1. 改写真实事件批（含 count==0 时推进运动）；
 * 2. 主动注入 STEP_COUNTER 事件：设备静止时 SensorService 不产生计步事件，
 *    被动改写无事可做导致计步器回 0；此处按步频周期构造虚拟 STEP_COUNTER
 *    事件追加到缓冲尾部，返回新 count 让原函数分发给所有连接。
 * 返回处理后的 count（可能 +1）；trampoline 用返回值覆盖原 x2。
 */
#define ZVE_INJECT_MAX_COUNT 128 /* 缓冲 this+0x270 容量至少 256 槽，保守上限 */
#define ZVE_INJECT_MIN_PERIOD_NS 100000000LL /* 100ms */

__attribute__((used, noinline))
size_t zve_process_batch(const zve_sensor_event_t *events, size_t count) {
    if (events == NULL) return count;
    pthread_mutex_lock(&g_mutex);
    if (!g_cfg.enabled || g_motion.step_freq_eff <= 0) {
        pthread_mutex_unlock(&g_mutex);
        return count;
    }

    /* 1) 推进运动 + 改写真实批（count==0 也推进：时间流逝步数增长） */
    g_stats.rewrite_calls++;
    zve_motion_advance();
    zve_phone_update();
    if (count > 0) {
        uint64_t batch_ts = events[0].timestamp;
        if (batch_ts != 0 && batch_ts == g_last_batch_ts) {
            /* 已被其它 hook 处理：直接返回原 count（不重复注入） */
            pthread_mutex_unlock(&g_mutex);
            return count;
        }
        g_last_batch_ts = batch_ts;
        size_t rewritten = 0;
        for (size_t i = 0; i < count; i++) {
            zve_sensor_event_t *ev = (zve_sensor_event_t *)&events[i];
            g_stats.last_type = ev->type;
            switch (ev->type) {
                case TYPE_STEP_COUNTER:
                    if (g_step_handle == 0) g_step_handle = ev->sensor;
                    ev->data64 = g_motion.step_count;
                    rewritten++;
                    break;
                case TYPE_STEP_DETECTOR:
                    if (g_motion.steps_pending > 0) {
                        ev->data[0] = 1.0f;
                        g_motion.steps_pending--;
                        rewritten++;
                    }
                    break;
                case TYPE_ACCELEROMETER:
                    zve_gen_accel(ev);
                    rewritten++;
                    break;
                case TYPE_LINEAR_ACCELERATION:
                    zve_gen_accel(ev);
                    zve_gen_linear_accel(ev);
                    rewritten++;
                    break;
                case TYPE_GRAVITY:
                    zve_gen_gravity(ev);
                    rewritten++;
                    break;
                case TYPE_GYROSCOPE:
                    zve_gen_gyro(ev);
                    rewritten++;
                    break;
                case TYPE_MAGNETIC_FIELD:
                    zve_gen_magnetic(ev);
                    rewritten++;
                    break;
                default:
                    break;
            }
        }
        if (rewritten > 0) {
            g_stats.events_rewritten += rewritten;
            g_stats.delivery_verified = 1;
        }
    }

    /* 2) 主动注入 STEP_COUNTER（若批内已有 STEP_COUNTER 则跳过，避免重复） */
    int has_step = 0;
    for (size_t i = 0; i < count; i++) {
        if (((const zve_sensor_event_t *)&events[i])->type == TYPE_STEP_COUNTER) {
            has_step = 1;
            break;
        }
    }
    if (!has_step && count < ZVE_INJECT_MAX_COUNT && g_step_handle > 0) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        int64_t now_ns = (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
        double step_hz = g_motion.step_freq_eff / 60.0;
        int64_t period_ns = (step_hz > 0.0)
            ? (int64_t)(1000000000.0 / step_hz)
            : ZVE_INJECT_MIN_PERIOD_NS;
        if (period_ns < ZVE_INJECT_MIN_PERIOD_NS) period_ns = ZVE_INJECT_MIN_PERIOD_NS;
        if (g_last_inject_ns == 0 || (now_ns - g_last_inject_ns) >= period_ns) {
            zve_sensor_event_t *ev = (zve_sensor_event_t *)&events[count];
            memset(ev, 0, sizeof(*ev));
            ev->version = sizeof(zve_sensor_event_t); /* 0x68 */
            ev->sensor = g_step_handle;
            ev->type = TYPE_STEP_COUNTER;
            ev->timestamp = now_ns;
            ev->data64 = g_motion.step_count;
            g_last_inject_ns = now_ns;
            g_last_batch_ts = (uint64_t)now_ns; /* 后续 write/sendObjects 同批跳过 */
            g_stats.inject_count++;
            g_stats.events_rewritten++;
            g_stats.delivery_verified = 1;
            count++;
        }
    }
    pthread_mutex_unlock(&g_mutex);
    return count;
}

/*
 * arm64 重写桩：手工构建为字节序列，写入 libsensor.so 附近 mmap 的
 * 可执行页（inline hook 标准做法，规避 B 指令 ±128MB 距离限制）。
 *   入口参数与 write() 一致：x0=tube, x1=events, x2=count, w3=0x68
 *   保存 x0-x3 + LR → 调 zve_rewrite_events(events, count) → 恢复 → 尾跳原 helper。
 *
 * 布局（72 字节）：
 *   0x00 sub sp, sp, #0x30
 *   0x04 stp x0, x1, [sp]
 *   0x08 stp x2, x3, [sp, #0x10]
 *   0x0c str x30, [sp, #0x20]
 *   0x10 mov x0, x1
 *   0x14 mov x1, x2
 *   0x18 ldr x16, [pc, #8]
 *   0x1c blr x16
 *   0x20 <quad rewrite_addr>
 *   0x28 ldp x0, x1, [sp]
 *   0x2c ldp x2, x3, [sp, #0x10]
 *   0x30 ldr x30, [sp, #0x20]
 *   0x34 add sp, sp, #0x30
 *   0x38 ldr x17, [pc, #8]
 *   0x3c br x17
 *   0x40 <quad helper_addr>
 */
#define ZVE_STUB_SIZE 0x4c

static int zve_build_stub(uint8_t *out, uintptr_t rewrite_addr, uintptr_t helper_addr) {
    /* 0x00-0x23：保存现场 + 调用 rewrite（blr 返回地址=0x20，必须跳过字面量区） */
    static const uint32_t kStubHead[] = {
        0xD100C3FFu, /* 0x00 sub sp, sp, #0x30 */
        0xA90007E0u, /* 0x04 stp x0, x1, [sp] */
        0xA9010FE2u, /* 0x08 stp x2, x3, [sp, #0x10] */
        0xF90013FEu, /* 0x0c str x30, [sp, #0x20] */
        0xAA0103E0u, /* 0x10 mov x0, x1 */
        0xAA0203E1u, /* 0x14 mov x1, x2 */
        0x58000070u, /* 0x18 ldr x16, [pc, #12] -> 0x24 字面量 */
        0xD63F0200u, /* 0x1c blr x16（返回地址 0x20） */
        0x14000003u, /* 0x20 b 0x2c（跳过 0x24 字面量到恢复现场） */
    };
    /* 0x2c-0x43：恢复现场 + 尾跳 helper */
    static const uint32_t kStubTail[] = {
        0xA94007E0u, /* 0x2c ldp x0, x1, [sp] */
        0xA9410FE2u, /* 0x30 ldp x2, x3, [sp, #0x10] */
        0xF94013FEu, /* 0x34 ldr x30, [sp, #0x20] */
        0x9100C3FFu, /* 0x38 add sp, sp, #0x30 */
        0x58000051u, /* 0x3c ldr x17, [pc, #8] -> 0x44 字面量 */
        0xD61F0220u, /* 0x40 br x17 */
    };
    memcpy(out, kStubHead, sizeof(kStubHead));             /* 0x00-0x23 */
    *(uint64_t *)(out + 0x24) = (uint64_t)rewrite_addr;    /* 0x24-0x2b 字面量 */
    memcpy(out + 0x2c, kStubTail, sizeof(kStubTail));      /* 0x2c-0x43 */
    *(uint64_t *)(out + 0x44) = (uint64_t)helper_addr;     /* 0x44-0x4b 字面量 */
    return ZVE_STUB_SIZE;
}

/*
 * SensorEventConnection::sendEvents 入口 trampoline（96 字节）。
 * sendEvents 为成员函数：x0=this, x1=events, x2=count。
 * 原入口为 paciasp（PAC 签名），本桩必须在 SP 恢复原值后再 paciasp，
 * 保证函数尾部 autiasp 验签通过；跳回 sendEvents+4 继续执行原函数体。
 *   0x00 sub sp, sp, #0x20
 *   0x04 stp x0, x1, [sp]          （this, events）
 *   0x08 stp x2, x30, [sp, #0x10]  （count, LR）
 *   0x0c mov x0, x1                （events）
 *   0x10 mov x1, x2                （count）
 *   0x14 ldr x16, [pc, #0x38] -> 0x50 rewrite 字面量
 *   0x18 blr x16
 *   0x1c ldp x0, x1, [sp]
 *   0x20 ldp x2, x30, [sp, #0x10]
 *   0x24 add sp, sp, #0x20
 *   0x28 paciasp                   （SP=原值，签名原 LR）
 *   0x2c ldr x17, [pc, #0x28] -> 0x58 返回地址字面量
 *   0x30 br x17
 *   0x50 <quad rewrite_addr>
 *   0x58 <quad sendEvents+4>
 */
#define ZVE_SE_STUB_SIZE 0x60

static int zve_build_sendevents_stub(uint8_t *out, uintptr_t rewrite_addr, uintptr_t ret_addr) {
    static const uint32_t kSe[] = {
        0xD10083FFu, /* 0x00 sub sp, sp, #0x20 */
        0xA90007E0u, /* 0x04 stp x0, x1, [sp] */
        0xA9017BE2u, /* 0x08 stp x2, x30, [sp, #0x10] */
        0xAA0103E0u, /* 0x0c mov x0, x1 */
        0xAA0203E1u, /* 0x10 mov x1, x2 */
        0x580001F0u, /* 0x14 ldr x16, [pc, #0x3c] -> 0x50 */
        0xD63F0200u, /* 0x18 blr x16 */
        0xA94007E0u, /* 0x1c ldp x0, x1, [sp] */
        0xA9417BE2u, /* 0x20 ldp x2, x30, [sp, #0x10] */
        0x910083FFu, /* 0x24 add sp, sp, #0x20 */
        0xD503233Fu, /* 0x28 paciasp */
        0x58000171u, /* 0x2c ldr x17, [pc, #0x2c] -> 0x58 */
        0xD61F0220u, /* 0x30 br x17 */
    };
    memset(out, 0, ZVE_SE_STUB_SIZE);
    memcpy(out, kSe, sizeof(kSe));                     /* 0x00-0x33 */
    *(uint64_t *)(out + 0x50) = (uint64_t)rewrite_addr; /* 0x50-0x57 */
    *(uint64_t *)(out + 0x58) = (uint64_t)ret_addr;     /* 0x58-0x5f */
    return ZVE_SE_STUB_SIZE;
}

/*
 * sendEventsToAllClients 入口 trampoline（0x58 字节）。
 * sendEventsToAllClients(this=x0, connections=x1, count=x2)；
 * events 不在参数里，事件缓冲 = *(void**)(this + 0x270)（threadLoop 每轮 poll 填充）。
 * 原入口为 paciasp（PAC 签名），SP 恢复原值后再 paciasp，跳回 +4 继续原函数体。
 * 调用 zve_process_batch(events, count) 改写 + 主动注入，返回新 count 覆盖 x2。
 *   0x00 sub sp, sp, #0x40
 *   0x04 stp x0, x1, [sp]            （this, connections）
 *   0x08 stp x2, x3, [sp, #0x10]     （count, x3）
 *   0x0c str x30, [sp, #0x20]        （LR）
 *   0x10 ldr x0, [x0, #0x270]        （events = this->0x270）
 *   0x14 mov x1, x2                  （count）
 *   0x18 ldr x16, [pc, #0xc]  -> 0x24 process_batch 字面量
 *   0x1c blr x16
 *   0x20 b 0x2c                      跳过字面量
 *   0x24 <quad zve_process_batch>
 *   0x2c str x0, [sp, #0x28]         （暂存 new_count）
 *   0x30 ldp x0, x1, [sp]
 *   0x34 ldp x2, x3, [sp, #0x10]
 *   0x38 ldr x2, [sp, #0x28]         （count = new_count）
 *   0x3c ldr x30, [sp, #0x20]
 *   0x40 add sp, sp, #0x40
 *   0x44 paciasp                     （SP=原值，签名原 LR）
 *   0x48 ldr x17, [pc, #0x8]  -> 0x50 返回地址字面量
 *   0x4c br x17
 *   0x50 <quad sendEventsToAllClients+4>
 */
#define ZVE_BATCH_STUB_SIZE 0x58

static int zve_build_batch_stub(uint8_t *out, uintptr_t process_addr, uintptr_t ret_addr) {
    /* Literal pool at 0x24 must not be overwritten by the tail instructions. */
    static const uint32_t kBatchHead[] = {
        0xD10103FFu, /* 0x00 sub sp, sp, #0x40 */
        0xA90007E0u, /* 0x04 stp x0, x1, [sp] */
        0xA9010FE2u, /* 0x08 stp x2, x3, [sp, #0x10] */
        0xF90013FEu, /* 0x0c str x30, [sp, #0x20] */
        0xF9413800u, /* 0x10 ldr x0, [x0, #0x270] */
        0xAA0203E1u, /* 0x14 mov x1, x2 */
        0x58000070u, /* 0x18 ldr x16, [pc, #0xc] -> 0x24 */
        0xD63F0200u, /* 0x1c blr x16 */
        0x14000003u, /* 0x20 b 0x2c */
    };
    static const uint32_t kBatchTail[] = {
        0xF90017E0u, /* 0x2c str x0, [sp, #0x28] */
        0xA94007E0u, /* 0x30 ldp x0, x1, [sp] */
        0xA9410FE2u, /* 0x34 ldp x2, x3, [sp, #0x10] */
        0xF94017E2u, /* 0x38 ldr x2, [sp, #0x28] */
        0xF94013FEu, /* 0x3c ldr x30, [sp, #0x20] */
        0x910103FFu, /* 0x40 add sp, sp, #0x40 */
        0xD503233Fu, /* 0x44 paciasp */
        0x58000051u, /* 0x48 ldr x17, [pc, #0x8] -> 0x50 */
        0xD61F0220u, /* 0x4c br x17 */
    };
    memset(out, 0, ZVE_BATCH_STUB_SIZE);
    memcpy(out, kBatchHead, sizeof(kBatchHead));          /* 0x00-0x23 */
    *(uint64_t *)(out + 0x24) = (uint64_t)process_addr;   /* 0x24-0x2b */
    memcpy(out + 0x2c, kBatchTail, sizeof(kBatchTail));  /* 0x2c-0x4f */
    *(uint64_t *)(out + 0x50) = (uint64_t)ret_addr;       /* 0x50-0x57 */
    return ZVE_BATCH_STUB_SIZE;
}

/* 在目标库加载地址附近分配可执行页（±96MB 内保证 B 可达）。
 * system_server 库区密集，固定偏移候选几乎全被占用（EEXIST），
 * 因此直接扫描 /proc/self/maps 在 [lib_base-96MB, lib_base+96MB]
 * 区间内找未映射空洞，取空洞起始页 mmap。 */
static void *zve_alloc_exec_page(uintptr_t lib_base) {
    long page = sysconf(_SC_PAGESIZE);
    uintptr_t lo = (lib_base > 0x6000000u) ? lib_base - 0x6000000u : (uintptr_t)page;
    uintptr_t hi = lib_base + 0x6000000u;
    FILE *f = fopen("/proc/self/maps", "r");
    uint8_t *mem = NULL;
    if (f != NULL) {
        char line[512];
        uintptr_t prev_end = lo;
        while (fgets(line, sizeof(line), f) != NULL) {
            uintptr_t s = 0, e = 0;
            if (sscanf(line, "%zx-%zx", (size_t *)&s, (size_t *)&e) != 2) continue;
            if (e <= lo) continue;
            if (s >= hi) break;
            if (s > prev_end && (s - prev_end) >= (uintptr_t)page) {
                void *p = mmap((void *)prev_end, (size_t)page, PROT_READ | PROT_WRITE,
                               MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
                if (p != MAP_FAILED) {
                    mem = (uint8_t *)p;
                    break;
                }
            }
            if (e > prev_end) prev_end = e;
        }
        if (mem == NULL && hi - prev_end >= (uintptr_t)page) {
            void *p = mmap((void *)prev_end, (size_t)page, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
            if (p != MAP_FAILED) mem = (uint8_t *)p;
        }
        fclose(f);
    }
    if (mem == NULL) {
        /* 兜底：多候选区 + 普通匿名映射（距离可能不达标，由 patch 校验） */
        static const int64_t kOffsets[] = {
            0x4000000LL, -0x4000000LL,
            0x6000000LL, -0x6000000LL,
            0x7000000LL, -0x7000000LL,
        };
        for (size_t k = 0; k < sizeof(kOffsets) / sizeof(kOffsets[0]) && mem == NULL; k++) {
            uintptr_t base_hint = (uintptr_t)((int64_t)lib_base + kOffsets[k]);
            base_hint &= ~((uintptr_t)page - 1);
            for (int i = 0; i < 128; i++) {
                void *p = mmap((void *)(base_hint + (uintptr_t)i * (uintptr_t)page), (size_t)page,
                               PROT_READ | PROT_WRITE,
                               MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
                if (p != MAP_FAILED) {
                    mem = (uint8_t *)p;
                    break;
                }
            }
        }
        if (mem == NULL) {
            LOGW("stub mmap gap scan failed, fallback random: %s", strerror(errno));
            void *p = mmap(NULL, (size_t)page, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (p == MAP_FAILED) return NULL;
            mem = (uint8_t *)p;
        }
    }
    /* 返回 RW 页：调用者写入桩代码后自行 mprotect RX */
    LOGI("stub mem allocated at %p (lib_base=%p)", mem, (void *)lib_base);
    return mem;
}

/* ---------- inline hook ---------- */

/* AArch64 B 指令：0x14000000 | imm26 */
static int32_t zve_branch_imm26(uint32_t insn) {
    int32_t imm26 = (int32_t)(insn & 0x03FFFFFF);
    if (imm26 & 0x02000000) imm26 |= 0xFC000000; /* 符号扩展 */
    return imm26;
}

static int zve_patch_branch(uintptr_t from, uintptr_t to) {
    int64_t delta = (int64_t)to - (int64_t)from;
    if ((delta & 3) != 0) return -6;
    int64_t imm26 = delta >> 2;
    if (imm26 < -(1LL << 25) || imm26 >= (1LL << 25)) return -6; /* 超出 ±128MB */
    long page = sysconf(_SC_PAGESIZE);
    uintptr_t page_start = from & ~((uintptr_t)page - 1);
    if (mprotect((void *)page_start, (size_t)page, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) return -7;
    uint32_t *p = (uint32_t *)from;
    *p = (uint32_t)(0x14000000u | ((uint32_t)imm26 & 0x03FFFFFFu));
    __builtin___clear_cache((char *)from, (char *)(from + 4));
    mprotect((void *)page_start, (size_t)page, PROT_READ | PROT_EXEC);
    return 0;
}

static int zve_patch_restore(uintptr_t from, uint32_t insn) {
    long page = sysconf(_SC_PAGESIZE);
    uintptr_t page_start = from & ~((uintptr_t)page - 1);
    if (mprotect((void *)page_start, (size_t)page, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) return -7;
    uint32_t *p = (uint32_t *)from;
    *p = insn;
    __builtin___clear_cache((char *)from, (char *)(from + 4));
    mprotect((void *)page_start, (size_t)page, PROT_READ | PROT_EXEC);
    return 0;
}

#define WRITE_SYMBOL "_ZN7android16SensorEventQueue5writeERKNS_2spINS_7BitTubeEEEPK12ASensorEventm"

/*
 * 定位 libsensor.so 句柄：短名 dlopen 受 linker namespace 隔离限制
 * （Oplus15 system_server 实测 "library not found"），依次尝试：
 *   1) 短名（标准 namespace 可用时）
 *   2) /system/lib64 完整路径
 *   3) /proc/self/maps 解析加载基址 + 已知 vaddr 锚点（绕过 namespace）
 */
static void *zve_lib_handle(void) {
    static const char *kNames[] = {
        "libsensor.so",
        "/system/lib64/libsensor.so",
    };
    for (size_t i = 0; i < sizeof(kNames) / sizeof(kNames[0]); i++) {
        void *h = dlopen(kNames[i], RTLD_NOLOAD);
        if (h == NULL) h = dlopen(kNames[i], RTLD_NOW | RTLD_LOCAL);
        if (h != NULL) return h;
    }
    return NULL;
}

/* 解析 maps 中 libsensor.so 加载基址（第一个匹配映射的起始地址） */
static uintptr_t zve_lib_base_from_maps(const char *libname) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (f == NULL) return 0;
    char line[512];
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), f) != NULL) {
        if (strstr(line, libname) == NULL) continue;
        unsigned long long start = 0;
        if (sscanf(line, "%llx-", &start) == 1) {
            base = (uintptr_t)start;
            break;
        }
    }
    fclose(f);
    return base;
}

/* libsensor.so 中 SensorEventQueue::write 的 vaddr（Oplus15 静态分析锚点；
 * 加载后实际地址 = base + vaddr，且必须通过下方字节校验才生效） */
#define WRITE_VADDR_OPLUS15 0x11660u

/* libsensorservice.so 中 SensorEventConnection::sendEvents 的 vaddr。
 * sendEvents 是所有连接事件发送的汇聚点（含 Android 15 共享内存通道），
 * 在其入口改写 events 缓冲区即全局生效；Oplus15 实证字节：paciasp。 */
#define SENDEVENTS_VADDR_OPLUS15 0x29944u

/* libsensorservice.so 中 SensorService::sendEventsToAllClients 的 vaddr。
 * threadLoop 每轮 poll 后把 this+0x270 事件缓冲统一分发给所有连接
 * （BitTube + SharedMem + wakeup 直写均经此函数），入口改写即全局生效；
 * count==0 时仍被调用，可主动追加 STEP_COUNTER 事件。 */
#define SENDEVENTS_TOALL_VADDR_OPLUS15 0x28784u

static int zve_sendevents_hook_install(void) {
    uintptr_t base = zve_lib_base_from_maps("libsensorservice.so");
    if (base == 0) {
        LOGW("libsensorservice.so not found in /proc/self/maps");
        return -20;
    }
    void *fn = (void *)(base + SENDEVENTS_VADDR_OPLUS15);
    const uint32_t *insn = (const uint32_t *)fn;
    if (insn[0] != 0xD503233Fu) { /* paciasp */
        LOGW("unexpected sendEvents prologue word0=%08x", insn[0]);
        return -21;
    }
    uint8_t *stub = zve_alloc_exec_page(base);
    if (stub == NULL) return -8;
    zve_build_sendevents_stub(stub, (uintptr_t)&zve_rewrite_events, (uintptr_t)fn + 4);
    long page = sysconf(_SC_PAGESIZE);
    if (mprotect(stub, (size_t)page, PROT_READ | PROT_EXEC) != 0) {
        LOGW("sendevents stub mprotect RX failed: %s", strerror(errno));
        munmap(stub, (size_t)page);
        return -22;
    }
    g_se_orig_insn = insn[0];
    int rc = zve_patch_branch((uintptr_t)fn, (uintptr_t)stub);
    if (rc != 0) {
        LOGW("sendevents patch failed rc=%d fn=%p stub=%p", rc, fn, stub);
        munmap(stub, (size_t)page);
        return rc;
    }
    g_se_addr = fn;
    g_se_stub_mem = stub;
    LOGI("sendEvents hook installed: fn=%p stub=%p", fn, stub);
    return 0;
}

static int zve_sendevents_hook_uninstall(void) {
    if (g_se_addr == NULL) return 0;
    int rc = zve_patch_restore((uintptr_t)g_se_addr, g_se_orig_insn);
    if (rc != 0) {
        LOGW("sendevents restore failed rc=%d", rc);
        return rc;
    }
    if (g_se_stub_mem != NULL) {
        munmap(g_se_stub_mem, (size_t)sysconf(_SC_PAGESIZE));
        g_se_stub_mem = NULL;
    }
    g_se_addr = NULL;
    LOGI("sendEvents hook uninstalled (original restored)");
    return 0;
}

/*
 * SensorService::sendEventsToAllClients 入口 hook（全局汇聚点）：
 * threadLoop 每轮 poll 后把 this+0x270 的事件缓冲分发给所有连接
 * （BitTube + SharedMem + wakeup 直写），在入口改写缓冲即所有通道生效；
 * count==0 时也会被调用，可主动追加 STEP_COUNTER 事件稳定计步器。
 * 入口为 paciasp（PAC），trampoline 布局见 zve_build_batch_stub。
 */
static int zve_sendevents_toall_hook_install(void) {
    uintptr_t base = zve_lib_base_from_maps("libsensorservice.so");
    if (base == 0) {
        LOGW("libsensorservice.so not found for sendEventsToAllClients");
        return -40;
    }
    void *fn = (void *)(base + SENDEVENTS_TOALL_VADDR_OPLUS15);
    const uint32_t *insn = (const uint32_t *)fn;
    if (insn[0] != 0xD503233Fu) { /* paciasp */
        LOGW("unexpected sendEventsToAllClients prologue word0=%08x", insn[0]);
        return -41;
    }
    uint8_t *stub = zve_alloc_exec_page(base);
    if (stub == NULL) return -8;
    zve_build_batch_stub(stub, (uintptr_t)&zve_process_batch, (uintptr_t)fn + 4);
    long page = sysconf(_SC_PAGESIZE);
    if (mprotect(stub, (size_t)page, PROT_READ | PROT_EXEC) != 0) {
        LOGW("sendEventsToAllClients stub mprotect RX failed: %s", strerror(errno));
        munmap(stub, (size_t)page);
        return -42;
    }
    g_batch_orig_insn = insn[0];
    int rc = zve_patch_branch((uintptr_t)fn, (uintptr_t)stub);
    if (rc != 0) {
        LOGW("sendEventsToAllClients patch failed rc=%d fn=%p stub=%p", rc, fn, stub);
        munmap(stub, (size_t)page);
        return rc;
    }
    g_batch_addr = fn;
    g_batch_stub_mem = stub;
    LOGI("sendEventsToAllClients hook installed: fn=%p stub=%p", fn, stub);
    return 0;
}

static int zve_sendevents_toall_hook_uninstall(void) {
    if (g_batch_addr == NULL) return 0;
    int rc = zve_patch_restore((uintptr_t)g_batch_addr, g_batch_orig_insn);
    if (rc != 0) {
        LOGW("sendEventsToAllClients restore failed rc=%d", rc);
        return rc;
    }
    if (g_batch_stub_mem != NULL) {
        munmap(g_batch_stub_mem, (size_t)sysconf(_SC_PAGESIZE));
        g_batch_stub_mem = NULL;
    }
    g_batch_addr = NULL;
    LOGI("sendEventsToAllClients hook uninstalled (original restored)");
    return 0;
}

#define SENDOBJECTS_SYMBOL "_ZN7android7BitTube11sendObjectsERKNS_2spIS0_EEPKvmm"
#define SENDOBJECTS_VADDR_OPLUS15 0xc478u

/*
 * BitTube::sendObjects 入口 hook：所有 BitTube 写入的最终汇聚点。
 * Oplus 的 SensorService 可能绕过 SensorEventQueue::write 直接调用它，
 * hook 此入口可覆盖 write 路径 + Oplus 直调路径。
 * 入口为 paciasp（PAC），trampoline 复用 sendEvents 模板（x0=tube, x1=events, x2=count）。
 */
static int zve_sendobjects_hook_install(void) {
    void *fn = NULL;
    void *h = zve_lib_handle();
    if (h != NULL) {
        fn = dlsym(h, SENDOBJECTS_SYMBOL);
        if (fn != NULL) LOGI("resolved %s via dlopen (%p)", SENDOBJECTS_SYMBOL, fn);
    }
    uintptr_t lib_base = 0;
    if (fn == NULL) {
        lib_base = zve_lib_base_from_maps("libsensor.so");
        if (lib_base == 0) {
            LOGW("libsensor.so not found for sendObjects");
            return -30;
        }
        fn = (void *)(lib_base + SENDOBJECTS_VADDR_OPLUS15);
        LOGI("resolved %s via maps base=%p vaddr=0x%x", SENDOBJECTS_SYMBOL,
             (void *)lib_base, SENDOBJECTS_VADDR_OPLUS15);
    }
    const uint32_t *insn = (const uint32_t *)fn;
    if (insn[0] != 0xD503233Fu) { /* paciasp */
        LOGW("unexpected sendObjects prologue word0=%08x", insn[0]);
        return -31;
    }
    if (lib_base == 0) {
        Dl_info di;
        if (dladdr(fn, &di) != 0) lib_base = (uintptr_t)di.dli_fbase;
    }
    uint8_t *stub = zve_alloc_exec_page(lib_base);
    if (stub == NULL) return -8;
    zve_build_sendevents_stub(stub, (uintptr_t)&zve_rewrite_events, (uintptr_t)fn + 4);
    long page = sysconf(_SC_PAGESIZE);
    if (mprotect(stub, (size_t)page, PROT_READ | PROT_EXEC) != 0) {
        LOGW("sendobjects stub mprotect RX failed: %s", strerror(errno));
        munmap(stub, (size_t)page);
        return -32;
    }
    g_so_orig_insn = insn[0];
    int rc = zve_patch_branch((uintptr_t)fn, (uintptr_t)stub);
    if (rc != 0) {
        LOGW("sendobjects patch failed rc=%d fn=%p stub=%p", rc, fn, stub);
        munmap(stub, (size_t)page);
        return rc;
    }
    g_so_addr = fn;
    g_so_stub_mem = stub;
    LOGI("sendObjects hook installed: fn=%p stub=%p", fn, stub);
    return 0;
}

static int zve_sendobjects_hook_uninstall(void) {
    if (g_so_addr == NULL) return 0;
    int rc = zve_patch_restore((uintptr_t)g_so_addr, g_so_orig_insn);
    if (rc != 0) {
        LOGW("sendobjects restore failed rc=%d", rc);
        return rc;
    }
    if (g_so_stub_mem != NULL) {
        munmap(g_so_stub_mem, (size_t)sysconf(_SC_PAGESIZE));
        g_so_stub_mem = NULL;
    }
    g_so_addr = NULL;
    LOGI("sendObjects hook uninstalled (original restored)");
    return 0;
}

static int zve_write_hook_install(void) {
    void *write_fn = NULL;
    void *h = zve_lib_handle();
    if (h != NULL) {
        write_fn = dlsym(h, WRITE_SYMBOL);
        if (write_fn != NULL) {
            LOGI("resolved %s via dlopen (%p)", WRITE_SYMBOL, write_fn);
        }
    } else {
        LOGW("dlopen libsensor.so failed: %s", dlerror());
    }
    if (write_fn == NULL) {
        /* 兜底：maps 基址 + vaddr 锚点 */
        uintptr_t base = zve_lib_base_from_maps("libsensor.so");
        if (base == 0) {
            g_stats.last_error = -9;
            LOGW("libsensor.so not found in /proc/self/maps");
            return -9;
        }
        write_fn = (void *)(base + WRITE_VADDR_OPLUS15);
        LOGI("resolved %s via maps base=%p vaddr=0x%x", WRITE_SYMBOL, (void *)base, WRITE_VADDR_OPLUS15);
    }
    const uint32_t *insn = (const uint32_t *)write_fn;
    if (insn[0] != 0xD503245Fu) { /* bti c */
        g_stats.last_error = -3;
        LOGW("unexpected prologue at %p: word0=%08x", write_fn, insn[0]);
        return -3;
    }
    if (insn[1] != 0x52800D03u) { /* mov w3, #0x68 */
        g_stats.last_error = -4;
        LOGW("unexpected prologue at %p: word1=%08x", write_fn, insn[1]);
        return -4;
    }
    if ((insn[2] & 0xFC000000u) != 0x14000000u) { /* b */
        g_stats.last_error = -5;
        LOGW("unexpected branch at %p: word2=%08x", write_fn, insn[2]);
        return -5;
    }
    int32_t imm26 = zve_branch_imm26(insn[2]);
    uintptr_t helper = (uintptr_t)write_fn + 8 + (int64_t)imm26 * 4;

    Dl_info di;
    uintptr_t lib_base = 0;
    if (dladdr(write_fn, &di) != 0) lib_base = (uintptr_t)di.dli_fbase;
    g_stub_mem = zve_alloc_exec_page(lib_base);
    if (g_stub_mem == NULL) {
        g_stats.last_error = -8;
        LOGW("prepare stub mem failed (base=%p)", (void *)lib_base);
        return -8;
    }
    zve_build_stub(g_stub_mem, (uintptr_t)&zve_rewrite_events, helper);
    long page = sysconf(_SC_PAGESIZE);
    if (mprotect(g_stub_mem, (size_t)page, PROT_READ | PROT_EXEC) != 0) {
        LOGW("write stub mprotect RX failed: %s", strerror(errno));
        munmap(g_stub_mem, (size_t)page);
        g_stub_mem = NULL;
        return -23;
    }

    g_write_helper_addr = (void *)helper;
    g_orig_b_insn = insn[2];
    int rc = zve_patch_branch((uintptr_t)write_fn + 8, (uintptr_t)g_stub_mem);
    if (rc != 0) {
        g_stats.last_error = rc;
        int64_t d = (int64_t)g_stub_mem - (int64_t)((uintptr_t)write_fn + 8);
        LOGW("patch branch failed rc=%d write=%p stub=%p delta=%lld bytes", rc, write_fn, g_stub_mem, (long long)d);
        munmap(g_stub_mem, (size_t)page);
        g_stub_mem = NULL;
        return rc;
    }
    g_write_addr = write_fn;
    LOGI("native write hook installed: write=%p helper=%p stub=%p", write_fn, (void *)helper, g_stub_mem);
    return 0;
}

static int zve_write_hook_uninstall(void) {
    if (g_write_addr == NULL) return 0;
    int rc = zve_patch_restore((uintptr_t)g_write_addr + 8, g_orig_b_insn);
    if (rc != 0) {
        g_stats.last_error = rc;
        LOGW("restore branch failed rc=%d", rc);
        return rc;
    }
    if (g_stub_mem != NULL) {
        munmap(g_stub_mem, (size_t)sysconf(_SC_PAGESIZE));
        g_stub_mem = NULL;
    }
    g_write_addr = NULL;
    g_write_helper_addr = NULL;
    LOGI("native write hook uninstalled (original restored)");
    return 0;
}

/*
 * 安装策略（第八轮最稳定方案）：
 *   - sendEventsToAllClients hook：libsensorservice.so 全局汇聚点，
 *     覆盖 BitTube + SharedMem（Android 15 gralloc 通道）+ wakeup 直写；
 *     同时主动追加 STEP_COUNTER 事件稳定计步器（count==0 也调用）。
 *   - write hook：SensorEventQueue::write（BitTube 直写路径兜底）
 *   - sendObjects hook：BitTube::sendObjects（BitTube 最终汇聚兜底）
 * 同一事件批由 process_batch/rewrite 的批首 timestamp 去重，保证只推进一次。
 * g_stats.hooked: 4=三装（sendEventsToAllClients+write+sendObjects）,
 *                 3=write+sendObjects, 1=仅 write。
 */
static int zve_hook_install(void) {
    if (g_stats.hooked) return 1;
    g_stats.last_error = 0;
    int b_rc = zve_sendevents_toall_hook_install();
    int w_rc = zve_write_hook_install();
    int so_rc = zve_sendobjects_hook_install();
    if (b_rc != 0 && w_rc != 0 && so_rc != 0) {
        g_stats.last_error = (b_rc != 0) ? b_rc : w_rc;
        LOGW("all hooks failed batch=%d write=%d sendObjects=%d", b_rc, w_rc, so_rc);
        return g_stats.last_error;
    }
    int count = (b_rc == 0) + (w_rc == 0) + (so_rc == 0);
    if (count == 3) {
        g_stats.hooked = 4;
        LOGI("[✓] native hook installed via sendEventsToAllClients + write + sendObjects (hooked=4)");
    } else if (count == 2 && w_rc == 0 && so_rc == 0) {
        g_stats.hooked = 3;
        LOGW("[✓] native hook installed via write + sendObjects (hooked=3, batch failed=%d)", b_rc);
    } else {
        g_stats.hooked = 1;
        LOGW("[✓] native hook installed via write only (hooked=1, batch=%d so=%d)", b_rc, so_rc);
    }
    g_stats.events_rewritten = 0;
    g_stats.delivery_verified = 0;
    return 0;
}

static int zve_hook_uninstall(void) {
    if (!g_stats.hooked) return 0;
    int rc1 = zve_write_hook_uninstall();
    int rc2 = zve_sendobjects_hook_uninstall();
    int rc3 = zve_sendevents_toall_hook_uninstall();
    g_stats.hooked = 0;
    g_stats.events_rewritten = 0;
    g_stats.delivery_verified = 0;
    return (rc1 != 0) ? rc1 : ((rc2 != 0) ? rc2 : rc3);
}

/* ---------- JNI ---------- */

static jint JNICALL nativeInit(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return 0;
}

static jint JNICALL nativeHookInstall(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return (jint)zve_hook_install();
}

static jint JNICALL nativeHookUninstall(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return (jint)zve_hook_uninstall();
}

static jint JNICALL nativeSetConfig(
    JNIEnv *env, jclass clazz,
    jboolean enabled, jint mode, jfloat stepFrequency, jfloat speedKmh,
    jfloat amplitude, jboolean randomNoise, jfloat headingDeg, jlong initialStepCount,
    jint stepHandle) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_mutex);
    int was_enabled = g_cfg.enabled;
    int first_enable = (!was_enabled && enabled != 0);
    g_cfg.enabled = enabled != 0;
    g_cfg.mode = (int)mode;
    g_cfg.step_frequency = stepFrequency > 0 ? stepFrequency : 0.0f;
    g_cfg.speed_kmh = speedKmh;
    g_cfg.amplitude = amplitude;
    g_cfg.random_noise = randomNoise != 0;
    g_cfg.heading_deg = headingDeg;
    if (stepHandle > 0) g_step_handle = (int)stepHandle; /* Java 传入 STEP_COUNTER handle */
    if (enabled != 0) {
        if (first_enable && initialStepCount > 0) {
            g_motion.step_count = (uint64_t)initialStepCount;
        }
        zve_motion_apply_profile();
        /* 启用时重置时间基准：避免用旧基准算出超大 dt 导致跳步 */
        if (first_enable) g_motion.last_tick_ns = 0;
        g_last_inject_ns = 0;
    } else {
        /* 禁用时同样重置基准，下次启用从新起点开始 */
        g_motion.last_tick_ns = 0;
        g_last_inject_ns = 0;
    }
    pthread_mutex_unlock(&g_mutex);
    return 0;
}

static jstring JNICALL nativeGetStatus(JNIEnv *env, jclass clazz) {
    (void)clazz;
    pthread_mutex_lock(&g_mutex);
    char buf[640];
    snprintf(buf, sizeof(buf),
             "{\"hooked\":%d,\"enabled\":%d,\"writeAddr\":\"%p\",\"helperAddr\":\"%p\","
             "\"batchAddr\":\"%p\",\"sendObjectsAddr\":\"%p\","
             "\"eventsRewritten\":%llu,\"stepCount\":%llu,\"lastError\":%d,\"deliveryVerified\":%d,"
             "\"rewriteCalls\":%llu,\"lastType\":%d,\"injectCount\":%llu,\"stepHandle\":%d}",
             g_stats.hooked, g_cfg.enabled, g_write_addr, g_write_helper_addr,
             g_batch_addr, g_so_addr,
             (unsigned long long)g_stats.events_rewritten,
             (unsigned long long)g_motion.step_count,
             g_stats.last_error, g_stats.delivery_verified,
             (unsigned long long)g_stats.rewrite_calls, g_stats.last_type,
             (unsigned long long)g_stats.inject_count, g_step_handle);
    pthread_mutex_unlock(&g_mutex);
    return (*env)->NewStringUTF(env, buf);
}

static jlong JNICALL nativeGetStepCount(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return (jlong)g_motion.step_count;
}

static const JNINativeMethod kMethods[] = {
    {"nativeInit", "()I", (void *)nativeInit},
    {"nativeHookInstall", "()I", (void *)nativeHookInstall},
    {"nativeHookUninstall", "()I", (void *)nativeHookUninstall},
    {"nativeSetConfig", "(ZIFFFZFJI)I", (void *)nativeSetConfig},
    {"nativeGetStatus", "()Ljava/lang/String;", (void *)nativeGetStatus},
    {"nativeGetStepCount", "()J", (void *)nativeGetStepCount},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = (*env)->FindClass(env, "io/github/fairyxh/VirtualEnv/core/sensor/NativeSensorBridge");
    if (clazz == NULL) {
        LOGW("FindClass NativeSensorBridge failed");
        return JNI_ERR;
    }
    if ((*env)->RegisterNatives(env, clazz, kMethods,
                                (jint)(sizeof(kMethods) / sizeof(kMethods[0]))) != JNI_OK) {
        LOGW("RegisterNatives failed");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
