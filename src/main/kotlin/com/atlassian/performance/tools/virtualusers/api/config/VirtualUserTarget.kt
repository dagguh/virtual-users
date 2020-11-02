package com.atlassian.performance.tools.virtualusers.api.config

import java.net.URI

class VirtualUserTarget(
    /**
     * @since 3.12.0
     */
    val webApplication: URI,
    internal val userName: String,
    internal val password: String
)
