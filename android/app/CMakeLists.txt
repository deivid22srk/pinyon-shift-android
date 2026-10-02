# Android entry point for the Pinyon Shift native build.
#
# Gradle configures this file; the real targets live in the repository root
# (two levels up), which consumes the pinned ShiftGlue SDK and the generated
# recompiled sources under .local/generated (code generation runs before the
# Gradle build; see .github/workflows/build.yml).
cmake_minimum_required(VERSION 3.25)
project(pinyon_shift_android LANGUAGES C CXX)

get_filename_component(PINYON_SHIFT_HOST_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)

set(REXSDK_DIR "${PINYON_SHIFT_HOST_ROOT}/thirdparty/shiftglue-sdk" CACHE PATH
    "ShiftGlue SDK source tree" FORCE)
set(PINYON_SHIFT_GENERATED_ROOT "${PINYON_SHIFT_HOST_ROOT}/.local/generated" CACHE PATH
    "Root containing the generated main and module trees" FORCE)
set(PINYON_SHIFT_FROZEN_CODEGEN ON CACHE BOOL
    "Use the existing generated snapshot without invoking the code generator" FORCE)

add_subdirectory("${PINYON_SHIFT_HOST_ROOT}" pinyon_shift_host)
