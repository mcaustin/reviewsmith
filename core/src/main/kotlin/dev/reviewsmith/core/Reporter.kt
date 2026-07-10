package dev.reviewsmith.core

interface Reporter {
    fun report(result: RunResult): String
}
