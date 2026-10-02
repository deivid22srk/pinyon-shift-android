# Install script for directory: /home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3

# Set the install prefix
if(NOT DEFINED CMAKE_INSTALL_PREFIX)
  set(CMAKE_INSTALL_PREFIX "/usr/local")
endif()
string(REGEX REPLACE "/$" "" CMAKE_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

# Set the install configuration name.
if(NOT DEFINED CMAKE_INSTALL_CONFIG_NAME)
  if(BUILD_TYPE)
    string(REGEX REPLACE "^[^A-Za-z0-9_]+" ""
           CMAKE_INSTALL_CONFIG_NAME "${BUILD_TYPE}")
  else()
    set(CMAKE_INSTALL_CONFIG_NAME "RelWithDebInfo")
  endif()
  message(STATUS "Install configuration: \"${CMAKE_INSTALL_CONFIG_NAME}\"")
endif()

# Set the component getting installed.
if(NOT CMAKE_INSTALL_COMPONENT)
  if(COMPONENT)
    message(STATUS "Install component: \"${COMPONENT}\"")
    set(CMAKE_INSTALL_COMPONENT "${COMPONENT}")
  else()
    set(CMAKE_INSTALL_COMPONENT)
  endif()
endif()

# Install shared libraries without execute permission?
if(NOT DEFINED CMAKE_INSTALL_SO_NO_EXE)
  set(CMAKE_INSTALL_SO_NO_EXE "1")
endif()

# Is this installation the result of a crosscompile?
if(NOT DEFINED CMAKE_CROSSCOMPILING)
  set(CMAKE_CROSSCOMPILING "TRUE")
endif()

# Set path to fallback-tool for dependency-resolution.
if(NOT DEFINED CMAKE_OBJDUMP)
  set(CMAKE_OBJDUMP "/home/z/my-project/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/pkgconfig" TYPE FILE FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/sdl3.pc")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE STATIC_LIBRARY FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-artifacts/libSDL3rd.a")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE STATIC_LIBRARY FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-artifacts/libSDL3_testrd.a")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3/SDL3headersTargets.cmake")
    file(DIFFERENT _cmake_export_file_changed FILES
         "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3/SDL3headersTargets.cmake"
         "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/CMakeFiles/Export/35815d1d52a6ea1175d74784b559bdb6/SDL3headersTargets.cmake")
    if(_cmake_export_file_changed)
      file(GLOB _cmake_old_config_files "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3/SDL3headersTargets-*.cmake")
      if(_cmake_old_config_files)
        string(REPLACE ";" ", " _cmake_old_config_files_text "${_cmake_old_config_files}")
        message(STATUS "Old export file \"$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3/SDL3headersTargets.cmake\" will be replaced.  Removing files [${_cmake_old_config_files_text}].")
        unset(_cmake_old_config_files_text)
        file(REMOVE ${_cmake_old_config_files})
      endif()
      unset(_cmake_old_config_files)
    endif()
    unset(_cmake_export_file_changed)
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3" TYPE FILE FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/CMakeFiles/Export/35815d1d52a6ea1175d74784b559bdb6/SDL3headersTargets.cmake")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3/SDL3staticTargets.cmake")
    file(DIFFERENT _cmake_export_file_changed FILES
         "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3/SDL3staticTargets.cmake"
         "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/CMakeFiles/Export/35815d1d52a6ea1175d74784b559bdb6/SDL3staticTargets.cmake")
    if(_cmake_export_file_changed)
      file(GLOB _cmake_old_config_files "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3/SDL3staticTargets-*.cmake")
      if(_cmake_old_config_files)
        string(REPLACE ";" ", " _cmake_old_config_files_text "${_cmake_old_config_files}")
        message(STATUS "Old export file \"$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3/SDL3staticTargets.cmake\" will be replaced.  Removing files [${_cmake_old_config_files_text}].")
        unset(_cmake_old_config_files_text)
        file(REMOVE ${_cmake_old_config_files})
      endif()
      unset(_cmake_old_config_files)
    endif()
    unset(_cmake_export_file_changed)
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3" TYPE FILE FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/CMakeFiles/Export/35815d1d52a6ea1175d74784b559bdb6/SDL3staticTargets.cmake")
  if(CMAKE_INSTALL_CONFIG_NAME MATCHES "^([Rr][Ee][Ll][Ww][Ii][Tt][Hh][Dd][Ee][Bb][Ii][Nn][Ff][Oo])$")
    file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3" TYPE FILE FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/CMakeFiles/Export/35815d1d52a6ea1175d74784b559bdb6/SDL3staticTargets-relwithdebinfo.cmake")
  endif()
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3/SDL3testTargets.cmake")
    file(DIFFERENT _cmake_export_file_changed FILES
         "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3/SDL3testTargets.cmake"
         "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/CMakeFiles/Export/35815d1d52a6ea1175d74784b559bdb6/SDL3testTargets.cmake")
    if(_cmake_export_file_changed)
      file(GLOB _cmake_old_config_files "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3/SDL3testTargets-*.cmake")
      if(_cmake_old_config_files)
        string(REPLACE ";" ", " _cmake_old_config_files_text "${_cmake_old_config_files}")
        message(STATUS "Old export file \"$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3/SDL3testTargets.cmake\" will be replaced.  Removing files [${_cmake_old_config_files_text}].")
        unset(_cmake_old_config_files_text)
        file(REMOVE ${_cmake_old_config_files})
      endif()
      unset(_cmake_old_config_files)
    endif()
    unset(_cmake_export_file_changed)
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3" TYPE FILE FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/CMakeFiles/Export/35815d1d52a6ea1175d74784b559bdb6/SDL3testTargets.cmake")
  if(CMAKE_INSTALL_CONFIG_NAME MATCHES "^([Rr][Ee][Ll][Ww][Ii][Tt][Hh][Dd][Ee][Bb][Ii][Nn][Ff][Oo])$")
    file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3" TYPE FILE FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/CMakeFiles/Export/35815d1d52a6ea1175d74784b559bdb6/SDL3testTargets-relwithdebinfo.cmake")
  endif()
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/SDL3" TYPE FILE FILES
    "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/SDL3Config.cmake"
    "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/SDL3ConfigVersion.cmake"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include/SDL3" TYPE FILE FILES
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_assert.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_asyncio.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_atomic.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_audio.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_begin_code.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_bits.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_blendmode.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_camera.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_clipboard.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_close_code.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_copying.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_cpuinfo.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_dialog.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_dlopennote.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_egl.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_endian.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_error.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_events.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_filesystem.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_gamepad.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_gpu.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_guid.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_haptic.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_hidapi.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_hints.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_init.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_intrin.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_iostream.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_joystick.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_keyboard.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_keycode.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_loadso.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_locale.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_log.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_main.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_main_impl.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_messagebox.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_metal.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_misc.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_mouse.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_mutex.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_oldnames.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_opengl.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_opengl_glext.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_opengles.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_opengles2.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_opengles2_gl2.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_opengles2_gl2ext.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_opengles2_gl2platform.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_opengles2_khrplatform.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_openxr.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_pen.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_pixels.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_platform.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_platform_defines.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_power.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_process.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_properties.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_rect.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_render.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_scancode.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_sensor.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_stdinc.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_storage.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_surface.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_system.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_thread.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_time.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_timer.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_touch.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_tray.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_version.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_video.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_vulkan.h"
    "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/include-revision/SDL3/SDL_revision.h"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include/SDL3" TYPE FILE FILES
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_test.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_test_assert.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_test_common.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_test_compare.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_test_crc32.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_test_font.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_test_fuzzer.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_test_harness.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_test_log.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_test_md5.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/include/SDL3/SDL_test_memory.h"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/share/licenses/SDL3" TYPE FILE FILES "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/sdl3/LICENSE.txt")
endif()

string(REPLACE ";" "\n" CMAKE_INSTALL_MANIFEST_CONTENT
       "${CMAKE_INSTALL_MANIFEST_FILES}")
if(CMAKE_INSTALL_LOCAL_ONLY)
  file(WRITE "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/481pq2x1/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/sdl3/install_local_manifest.txt"
     "${CMAKE_INSTALL_MANIFEST_CONTENT}")
endif()
