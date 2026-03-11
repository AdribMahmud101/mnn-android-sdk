cmake_minimum_required(VERSION 3.18.1)
project("mnn-jni-bridge")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find MNN library
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN.so
)

# Include MNN headers
include_directories(${CMAKE_SOURCE_DIR}/include)

# Build JNI bridge library
add_library(mnn-jni-bridge SHARED
    mnn_engine.cpp
    mnn_tensor.cpp
    mnn_interpreter.cpp
)

# Link libraries
target_link_libraries(mnn-jni-bridge
    MNN
    android
    log
)
