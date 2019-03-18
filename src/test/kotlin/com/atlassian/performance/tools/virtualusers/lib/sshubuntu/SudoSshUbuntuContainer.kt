package com.atlassian.performance.tools.virtualusers.lib.sshubuntu

import com.atlassian.performance.tools.ssh.api.Ssh
import com.github.dockerjava.api.model.Ports

class SudoSshUbuntuContainer(
    val ssh: Ssh,
    val ports: Ports,
    val peerIp: String
)

