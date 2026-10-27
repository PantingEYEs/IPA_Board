cmake_minimum_required(VERSION 3.22.1)
project(ipa_rime_batch LANGUAGES CXX)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(RIME_RELATIVE "app/src/main/jniLibs/${ANDROID_ABI}/librime_jni.so")
set(RIME_LIBRARY "${REPO_ROOT}/${RIME_RELATIVE}")
# This bridge uses a private C++ type exposed by the pinned C API implementation.
# Reject binary replacement rather than risk reading a different Candidate/Menu ABI.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${RIME_LIBRARY}" "${CMAKE_CURRENT_SOURCE_DIR}/vendor/librime/manifest.json"
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/boost/manifest.json")
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/vendor/librime/manifest.json" BRIDGE_LOCK)
string(JSON EXPECTED_HASH GET "${BRIDGE_LOCK}" runtimeSha256 "${ANDROID_ABI}")
foreach(VENDOR librime boost)
    file(READ "${CMAKE_CURRENT_SOURCE_DIR}/vendor/${VENDOR}/manifest.json" HEADER_LOCK)
    string(JSON HEADER_COUNT LENGTH "${HEADER_LOCK}" headers)
    math(EXPR LAST_HEADER "${HEADER_COUNT} - 1")
    foreach(INDEX RANGE ${LAST_HEADER})
        string(JSON HEADER MEMBER "${HEADER_LOCK}" headers ${INDEX})
        string(JSON HEADER_HASH GET "${HEADER_LOCK}" headers "${HEADER}")
        set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
            "${CMAKE_CURRENT_SOURCE_DIR}/vendor/${VENDOR}/${HEADER}")
        file(SHA256 "${CMAKE_CURRENT_SOURCE_DIR}/vendor/${VENDOR}/${HEADER}" ACTUAL_HEADER_HASH)
        if(NOT ACTUAL_HEADER_HASH STREQUAL HEADER_HASH)
            message(FATAL_ERROR "Pinned header hash mismatch: ${VENDOR}/${HEADER}")
        endif()
    endforeach()
endforeach()
file(SHA256 "${RIME_LIBRARY}" ACTUAL_HASH)
if(NOT EXPECTED_HASH OR NOT ACTUAL_HASH STREQUAL EXPECTED_HASH)
    message(FATAL_ERROR "Bundled Rime hash mismatch: review the bridge ABI before replacing Rime")
endif()

add_library(ipa_rime_batch SHARED rime_batch.cpp)
target_compile_features(ipa_rime_batch PRIVATE cxx_std_17)
target_include_directories(ipa_rime_batch PRIVATE include)
target_include_directories(ipa_rime_batch SYSTEM PRIVATE vendor/librime vendor/boost)
target_compile_options(ipa_rime_batch PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
# Link the existing packaged runtime; do not compile/copy another Rime or initialize a second service.
# A link flag avoids importing/packaging the prebuilt library a second time through CMake's model.
target_link_directories(ipa_rime_batch PRIVATE "${REPO_ROOT}/app/src/main/jniLibs/${ANDROID_ABI}")
target_link_libraries(ipa_rime_batch PRIVATE "-l:librime_jni.so")
target_link_options(ipa_rime_batch PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
set_property(TARGET ipa_rime_batch APPEND PROPERTY LINK_DEPENDS "${RIME_LIBRARY}")
