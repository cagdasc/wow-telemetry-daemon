package com.cacaosd.wowtelemetry.agent.di

import ai.koog.utils.time.KoogClock
import com.cacaosd.wowtelemetry.agent.CombatEventNarrator
import org.koin.dsl.module

fun agentModule() = module {
    single<KoogClock> { KoogClock.System }
    single { CombatEventNarrator(get(), get(), get()) }
}