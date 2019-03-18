package com.atlassian.performance.tools.virtualusers.lib.infrastructure

import com.atlassian.performance.tools.infrastructure.api.database.Database
import com.atlassian.performance.tools.infrastructure.api.database.MySqlDatabase
import com.atlassian.performance.tools.ssh.api.SshConnection
import java.net.URI

class FixedMysqlDatabase(
    private val brokenMysqlDatabase: MySqlDatabase
) : Database {
    override fun setup(ssh: SshConnection): String {
        CopyPastedDocker().install(ssh)
        ssh.execute("service docker start")
        return brokenMysqlDatabase.setup(ssh)
    }

    override fun start(jira: URI, ssh: SshConnection) {
        return brokenMysqlDatabase.start(jira, ssh)
    }
}
