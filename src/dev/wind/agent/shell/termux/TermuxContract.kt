package dev.wind.agent.shell.termux

object TermuxContract {
    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"
    const val EXTRA_RESULT_STDOUT = "stdout"
    const val EXTRA_RESULT_STDERR = "stderr"
    const val EXTRA_RESULT_EXIT_CODE = "exitCode"
    const val EXTRA_RESULT_ERR = "err"
    const val EXTRA_RESULT_ERRMSG = "errmsg"

    const val TERMUX_BIN_PREFIX = "/data/data/com.termux/files/usr/bin/"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"

    fun resolveExecutable(executable: String, termuxProjectDir: String): String {
        val clean = executable.trim()
        if (clean.isEmpty()) throw IllegalArgumentException("Executable cannot be empty")
        if (clean.contains("..") || clean.contains("%2e")) {
            throw SecurityException("Directory traversal in executable is prohibited: $executable")
        }
        if (clean.startsWith("./")) {
            val rel = clean.substring(2)
            if (rel.isEmpty() || rel.contains("/") || rel.contains("\\")) {
                throw SecurityException("Project scripts must reside directly in project root: $executable")
            }
            return "$termuxProjectDir/$rel"
        }
        if (clean.contains("/") || clean.contains("\\")) {
            throw SecurityException("Path in executable name is prohibited. Use a bare command (e.g. 'git') or './script': $executable")
        }
        return TERMUX_BIN_PREFIX + clean
    }
}
