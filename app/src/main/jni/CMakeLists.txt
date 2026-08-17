cmake_minimum_required(VERSION 3.22.1)
project(zvesensor C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(zvesensor SHARED zve_sensor.c)

target_compile_options(zvesensor PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(zvesensor PRIVATE log m)
