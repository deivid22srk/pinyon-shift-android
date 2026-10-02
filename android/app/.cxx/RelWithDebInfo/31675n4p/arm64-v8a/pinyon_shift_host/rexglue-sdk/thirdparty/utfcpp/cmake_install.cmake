# Install script for directory: /home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/utfcpp

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
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/share/utf8cpp/cmake/utf8cppTargets.cmake")
    file(DIFFERENT _cmake_export_file_changed FILES
         "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/share/utf8cpp/cmake/utf8cppTargets.cmake"
         "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/utfcpp/CMakeFiles/Export/92c204fa88b81950ec0385cfa3e48c04/utf8cppTargets.cmake")
    if(_cmake_export_file_changed)
      file(GLOB _cmake_old_config_files "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/share/utf8cpp/cmake/utf8cppTargets-*.cmake")
      if(_cmake_old_config_files)
        string(REPLACE ";" ", " _cmake_old_config_files_text "${_cmake_old_config_files}")
        message(STATUS "Old export file \"$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/share/utf8cpp/cmake/utf8cppTargets.cmake\" will be replaced.  Removing files [${_cmake_old_config_files_text}].")
        unset(_cmake_old_config_files_text)
        file(REMOVE ${_cmake_old_config_files})
      endif()
      unset(_cmake_old_config_files)
    endif()
    unset(_cmake_export_file_changed)
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/share/utf8cpp/cmake" TYPE FILE FILES "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/utfcpp/CMakeFiles/Export/92c204fa88b81950ec0385cfa3e48c04/utf8cppTargets.cmake")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/share/utf8cpp/cmake" TYPE FILE FILES
    "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/utfcpp/utf8cppConfig.cmake"
    "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/utfcpp/utf8cppConfigVersion.cmake"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include/utf8cpp" TYPE FILE FILES "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/utfcpp/source/utf8.h")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include/utf8cpp" TYPE DIRECTORY FILES "/home/z/my-project/work/pinyon-shift/thirdparty/shiftglue-sdk/thirdparty/utfcpp/source/utf8")
endif()

string(REPLACE ";" "\n" CMAKE_INSTALL_MANIFEST_CONTENT
       "${CMAKE_INSTALL_MANIFEST_FILES}")
if(CMAKE_INSTALL_LOCAL_ONLY)
  file(WRITE "/home/z/my-project/work/pinyon-shift/android/app/.cxx/RelWithDebInfo/31675n4p/arm64-v8a/pinyon_shift_host/rexglue-sdk/thirdparty/utfcpp/install_local_manifest.txt"
     "${CMAKE_INSTALL_MANIFEST_CONTENT}")
endif()
