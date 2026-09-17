cmake_minimum_required(VERSION 3.20)

project(MnoteWindows VERSION 1.11.0 LANGUAGES CXX RC)

# Pinned, hash-verified build dependency; no third-party runtime is installed.
set(JSON_HEADER "${CMAKE_CURRENT_BINARY_DIR}/deps/json.hpp")
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/deps")
if(NOT EXISTS "${JSON_HEADER}")
    file(DOWNLOAD "https://github.com/nlohmann/json/releases/download/v3.11.3/json.hpp"
        "${JSON_HEADER}" TLS_VERIFY ON
        EXPECTED_HASH SHA256=9bea4c8066ef4a1c206b2be5a36302f8926f7fdc6087af5d20b417d0cf103ea6)
endif()
file(SHA256 "${JSON_HEADER}" JSON_HASH)
if(NOT JSON_HASH STREQUAL "9bea4c8066ef4a1c206b2be5a36302f8926f7fdc6087af5d20b417d0cf103ea6")
    message(FATAL_ERROR "Unexpected JSON dependency hash")
endif()

add_executable(mnote WIN32
    src/main.cpp
    src/sync.cpp
    src/library.cpp
    src/context.cpp
    src/workspace.cpp
    src/updater.cpp
    src/mnote.rc
)
target_include_directories(mnote SYSTEM PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/deps")

target_compile_features(mnote PRIVATE cxx_std_17)
target_compile_definitions(mnote PRIVATE
    UNICODE
    _UNICODE
    WINVER=0x0A00
    _WIN32_WINNT=0x0A00
    NOMINMAX
)

if(MSVC)
    target_compile_options(mnote PRIVATE /W4 /permissive- /EHsc /utf-8)
else()
    target_compile_options(mnote PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -Wconversion
        -Wshadow
    )
endif()

if(MINGW)
    # Produce one portable EXE without requiring libgcc/libstdc++ DLLs.
    target_link_options(mnote PRIVATE
        -municode
        -static
        -static-libgcc
        -static-libstdc++
    )
endif()

target_link_libraries(mnote PRIVATE
    comdlg32
    comctl32
    gdiplus
    gdi32
    ole32
    shell32
    user32
    winhttp
    ws2_32
    crypt32
    bcrypt
    oleaut32
    uuid
    dwmapi
)

install(TARGETS mnote RUNTIME DESTINATION .)
