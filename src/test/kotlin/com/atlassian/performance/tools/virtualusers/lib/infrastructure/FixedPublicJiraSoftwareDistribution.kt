package com.atlassian.performance.tools.virtualusers.lib.infrastructure

import com.atlassian.performance.tools.infrastructure.api.distribution.ProductDistribution
import com.atlassian.performance.tools.infrastructure.api.distribution.PublicJiraSoftwareDistribution
import com.atlassian.performance.tools.ssh.api.SshConnection
import java.net.URI
import java.time.Duration

/**
 * Works around a bug in [PublicJiraSoftwareDistribution]:
 * ```
 * pget: /software/jira/downloads/atlassian-jira-software-7.13.0.tar.gz: Cannot assign requested address
 * ```
 */
class FixedPublicJiraSoftwareDistribution(
    private val version: String
) : ProductDistribution {

    override fun install(ssh: SshConnection, destination: String): String {
        val archiveName = "atlassian-jira-software-$version.tar.gz"
        val jiraArchiveUri = URI("https://product-downloads.atlassian.com/software/jira/downloads/$archiveName")
        ssh.execute("mkdir -p $destination")
        ssh.execute("wget -q $jiraArchiveUri -O $destination/$archiveName", Duration.ofMinutes(2))
        ssh.execute("tar -xzf $destination/$archiveName --directory $destination", Duration.ofMinutes(1))
        return "$destination/atlassian-jira-software-$version-standalone"
    }
}
