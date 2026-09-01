cmake_minimum_required(VERSION 3.20)

project(MnoteWindows VERSION 0.1.0 LANGUAGES CXX)

add_executable(mnote WIN32
    src/main.cpp
    src/sync.cpp
)

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
    comctl32
    gdiplus
    gdi32
    ole32
    shell32
    user32
    winhttp
    ws2_32
)

install(TARGETS mnote RUNTIME DESTINATION .)
