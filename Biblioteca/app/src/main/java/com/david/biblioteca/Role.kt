package com.david.biblioteca


enum class Role(val apiName: String, val title: String) {
    TEACHER("profesor", "profesor"),
    STUDENT("estudiante", "estudiante")
}