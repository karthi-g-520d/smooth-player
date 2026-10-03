package com.example.smoothplayer.engine.api

import com.example.smoothplayer.engine.api.model.CapabilityReport

/** Produces one capability report for the current device. Blocks while probing, so call it off the main thread. */
public fun interface CapabilityProbe {
    public fun probe(): CapabilityReport
}
