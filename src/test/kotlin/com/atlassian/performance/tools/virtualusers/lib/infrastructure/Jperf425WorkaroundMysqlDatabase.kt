package com.atlassian.performance.tools.virtualusers.lib.infrastructure


import com.atlassian.performance.tools.infrastructure.api.database.Database
import com.atlassian.performance.tools.infrastructure.api.database.MySqlDatabase
import com.atlassian.performance.tools.infrastructure.api.dataset.DatasetPackage
import com.atlassian.performance.tools.infrastructure.api.os.Ubuntu
import com.atlassian.performance.tools.ssh.api.SshConnection
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Works around [JPERF-425](https://ecosystem.atlassian.net/browse/JPERF-425).
 */
class Jperf425WorkaroundMysqlDatabase(
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

/**
 * @param maxConnections MySQL `max_connections` parameter.
 */
class DindMySqlDatabase(
    private val source: DatasetPackage,
    private val maxConnections: Int
) : Database {
    private val logger: Logger = LogManager.getLogger(this::class.java)

    private val image: DockerImage = DockerImage(
        name = "mysql:5.6.42",
        pullTimeout = Duration.ofMinutes(5)
    )
    private val ubuntu = Ubuntu()

    /**
     * Uses MySQL defaults.
     */
    constructor(
        source: DatasetPackage
    ) : this(
        source = source,
        maxConnections = 151
    )

    override fun setup(ssh: SshConnection): String {
        val mysqlData = source.download(ssh)
        image.run(
            ssh = ssh,
            parameters = "-p 3306:3306 -v `realpath $mysqlData`:/var/lib/mysql",
            arguments = "--skip-grant-tables --max_connections=$maxConnections"
        )
        return mysqlData
    }

    override fun start(jira: URI, ssh: SshConnection) {
        waitForMysql(ssh)
        ssh.execute("""mysql -h 127.0.0.1  -u root -e "UPDATE jiradb.propertystring SET propertyvalue = '$jira' WHERE id IN (select id from jiradb.propertyentry where property_key like '%baseurl%');" """)
    }

    private fun waitForMysql(ssh: SshConnection) {
        ubuntu.install(ssh, listOf("mysql-client"))
        val mysqlStart = Instant.now()
        while (!ssh.safeExecute("mysql -h 127.0.0.1 -u root -e 'select 1;'").isSuccessful()) {
            if (Instant.now() > mysqlStart + Duration.ofMinutes(15)) {
                throw RuntimeException("MySql didn't start in time")
            }
            logger.debug("Waiting for MySQL...")
            Thread.sleep(Duration.ofSeconds(10).toMillis())
        }
    }
}

/**
 * Copy-pasted verbatim from [infrastructure:4.11.0](https://github.com/atlassian/infrastructure/blob/release-4.11.0/src/main/kotlin/com/atlassian/performance/tools/infrastructure/Docker.kt)
 * It's here just to interject the Docker install and MySQL setup with a Docker start.
 */
private class CopyPastedDocker {

    private val ubuntu = Ubuntu()

    /**
     * See the [official guide](https://docs.docker.com/engine/installation/linux/docker-ce/ubuntu/#install-docker-ce).
     */
    fun install(
        ssh: SshConnection
    ) {
        ubuntu.install(
            ssh = ssh,
            packages = listOf(
                "apt-transport-https",
                "ca-certificates",
                "curl",
                "software-properties-common"
            ),
            timeout = Duration.ofMinutes(2)
        )
        val release = ssh.execute("lsb_release -cs").output
        val repository = "deb [arch=amd64] https://download.docker.com/linux/ubuntu $release stable"
        ssh.execute("sudo add-apt-repository \"$repository\"")
        ssh.execute("curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -")
        val version = "17.09.0~ce-0~ubuntu"
        ubuntu.install(
            ssh = ssh,
            packages = listOf("docker-ce=$version"),
            timeout = Duration.ofSeconds(180)
        )
    }
}

internal class DockerImage(
    private val name: String,
    private val pullTimeout: Duration = Duration.ofMinutes(1)
) {

    private val docker = CopyPastedDocker()

    fun run(
        ssh: SshConnection,
        parameters: String = "",
        arguments: String = ""
    ): String {
        docker.install(ssh)
        val containerName = "jpt-" + UUID.randomUUID()
        ssh.execute(
            cmd = "sudo docker pull $name",
            timeout = pullTimeout,
            stdout = Level.TRACE,
            stderr = Level.WARN
        )
        val dindFix = "-v /var/run/docker.sock:/var/run/docker.sock"
        ssh.execute("sudo docker run -d $parameters $dindFix --name $containerName $name $arguments")
        return containerName
    }
}
