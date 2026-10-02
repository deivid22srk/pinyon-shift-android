# Install script for directory: /home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt

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

if(CMAKE_INSTALL_COMPONENT STREQUAL "fmt_core" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE STATIC_LIBRARY FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-artifacts/libfmtrd.a")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "fmt_core" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include/fmt" TYPE FILE FILES
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/args.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/base.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/chrono.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/color.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/compile.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/core.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/format.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/format-inl.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/os.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/ostream.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/printf.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/ranges.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/std.h"
    "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/fmt/include/fmt/xchar.h"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "fmt_core" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/fmt" TYPE FILE FILES
    "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/fmt/fmt-config.cmake"
    "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/fmt/fmt-config-version.cmake"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "fmt_core" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/fmt/fmt-targets.cmake")
    file(DIFFERENT _cmake_export_file_changed FILES
         "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/fmt/fmt-targets.cmake"
         "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/fmt/CMakeFiles/Export/b834597d9b1628ff12ae4314c3a2e4b8/fmt-targets.cmake")
    if(_cmake_export_file_changed)
      file(GLOB _cmake_old_config_files "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/fmt/fmt-targets-*.cmake")
      if(_cmake_old_config_files)
        string(REPLACE ";" ", " _cmake_old_config_files_text "${_cmake_old_config_files}")
        message(STATUS "Old export file \"$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/fmt/fmt-targets.cmake\" will be replaced.  Removing files [${_cmake_old_config_files_text}].")
        unset(_cmake_old_config_files_text)
        file(REMOVE ${_cmake_old_config_files})
      endif()
      unset(_cmake_old_config_files)
    endif()
    unset(_cmake_export_file_changed)
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/fmt" TYPE FILE FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/fmt/CMakeFiles/Export/b834597d9b1628ff12ae4314c3a2e4b8/fmt-targets.cmake")
  if(CMAKE_INSTALL_CONFIG_NAME MATCHES "^([Rr][Ee][Ll][Ww][Ii][Tt][Hh][Dd][Ee][Bb][Ii][Nn][Ff][Oo])$")
    file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/fmt" TYPE FILE FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/fmt/CMakeFiles/Export/b834597d9b1628ff12ae4314c3a2e4b8/fmt-targets-relwithdebinfo.cmake")
  endif()
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "fmt_core" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/pkgconfig" TYPE FILE FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/fmt/fmt.pc")
endif()

string(REPLACE ";" "\n" CMAKE_INSTALL_MANIFEST_CONTENT
       "${CMAKE_INSTALL_MANIFEST_FILES}")
if(CMAKE_INSTALL_LOCAL_ONLY)
  file(WRITE "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/fmt/install_local_manifest.txt"
     "${CMAKE_INSTALL_MANIFEST_CONTENT}")
endif()
