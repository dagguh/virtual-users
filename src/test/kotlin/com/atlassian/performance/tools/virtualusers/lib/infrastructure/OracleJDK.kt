package com.atlassian.performance.tools.virtualusers.lib.infrastructure

import com.atlassian.performance.tools.infrastructure.api.jvm.OracleJDK
import com.atlassian.performance.tools.infrastructure.api.jvm.VersionedJavaDevelopmentKit
import com.atlassian.performance.tools.infrastructure.api.os.Ubuntu
import com.atlassian.performance.tools.ssh.api.SshConnection

class FixedOracleJDK(
    private val jdk: OracleJDK
) : VersionedJavaDevelopmentKit by (jdk) {

    override fun install(connection: SshConnection) {
        Ubuntu().install(connection, listOf("curl"))
        jdk.install(connection)
    }
}
