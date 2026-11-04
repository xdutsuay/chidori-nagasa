package com.druk.lmplayground.computer.desktop

/**
 * POSIX shell quoting for [DesktopBridge.execInDesktop], whose contract takes a
 * command *string*. Each argv element is wrapped in single quotes (inside which
 * the shell interprets nothing) and embedded single quotes become `'\''`, so the
 * guest shell always sees exactly the argv we built: no expansion, globbing,
 * substitution, redirection or command separation is possible. NUL is rejected
 * (it cannot be carried in an argv at all).
 */
object ShellQuote {
    fun quote(arg: String): String {
        require('\u0000' !in arg) { "NUL in argument" }
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    fun join(argv: List<String>): String {
        require(argv.isNotEmpty()) { "empty argv" }
        return argv.joinToString(" ") { quote(it) }
    }
}
