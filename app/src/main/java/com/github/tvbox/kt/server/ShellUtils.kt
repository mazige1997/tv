package com.github.tvbox.kt.server

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.List

/** shell 命令工具,Kotlin 移植(与旧版 ShellUtils 一致)。 */
object ShellUtils {
    const val COMMAND_SU = "su"
    const val COMMAND_SH = "sh"
    const val COMMAND_EXIT = "exit\n"
    const val COMMAND_LINE_END = "\n"

    /** 查看是否有了 root 权限 */
    fun checkRootPermission(): Boolean {
        return execCommand("echo root", true, false).result == 0
    }

    fun execCommand(command: String?, isRoot: Boolean): CommandResult {
        return execCommand(arrayOf(command), isRoot, true)
    }

    fun execCommand(commands: List<String>?, isRoot: Boolean): CommandResult {
        return execCommand(commands?.map<String, String?> { it }?.toTypedArray(), isRoot, true)
    }

    fun execCommand(commands: Array<String?>?, isRoot: Boolean): CommandResult {
        return execCommand(commands, isRoot, true)
    }

    fun execCommand(command: String?, isRoot: Boolean, isNeedResultMsg: Boolean): CommandResult {
        return execCommand(arrayOf(command), isRoot, isNeedResultMsg)
    }

    fun execCommand(commands: List<String>?, isRoot: Boolean, isNeedResultMsg: Boolean): CommandResult {
        return execCommand(commands?.map<String, String?> { it }?.toTypedArray(), isRoot, isNeedResultMsg)
    }

    fun execCommand(commands: Array<String?>?, isRoot: Boolean, isNeedResultMsg: Boolean): CommandResult {
        var result = -1
        var process: Process? = null
        var successResult: BufferedReader? = null
        var errorResult: BufferedReader? = null
        var successMsg: StringBuilder? = null
        var errorMsg: StringBuilder? = null
        var os: DataOutputStream? = null
        try {
            process = Runtime.getRuntime().exec(if (isRoot) COMMAND_SU else COMMAND_SH)
            os = DataOutputStream(process.outputStream)
            for (command in commands ?: arrayOf()) {
                if (command == null) {
                    continue
                }
                os.write(command.toByteArray())
                os.writeBytes(COMMAND_LINE_END)
                os.flush()
            }
            os.writeBytes(COMMAND_EXIT)
            os.flush()
            result = process.waitFor()
            if (isNeedResultMsg) {
                successMsg = StringBuilder()
                errorMsg = StringBuilder()
                successResult = BufferedReader(InputStreamReader(process.inputStream))
                errorResult = BufferedReader(InputStreamReader(process.errorStream))
                var s: String?
                while (successResult.readLine().also { s = it } != null) {
                    successMsg.append(s)
                }
                while (errorResult.readLine().also { s = it } != null) {
                    errorMsg.append(s)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                os?.close()
                successResult?.close()
                errorResult?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            process?.destroy()
        }
        return CommandResult(
            result,
            successMsg?.toString(),
            errorMsg?.toString()
        )
    }

    class CommandResult(var result: Int = -1, var successMsg: String?, var errorMsg: String?) {
        override fun toString(): String {
            return "CommandResult{" +
                "result=" + result +
                ", successMsg='" + successMsg + '\'' +
                ", errorMsg='" + errorMsg + '\'' +
                '}'
        }
    }
}
