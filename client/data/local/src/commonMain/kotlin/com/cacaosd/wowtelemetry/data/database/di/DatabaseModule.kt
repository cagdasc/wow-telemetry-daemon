package com.cacaosd.wowtelemetry.data.database.di

import com.cacaosd.wowtelemetry.data.database.AppDatabase
import com.cacaosd.wowtelemetry.data.database.CharacterProfileDatabase
import com.cacaosd.wowtelemetry.data.database.RoomCharacterProfileRepository
import com.cacaosd.wowtelemetry.data.database.RoomNpcMetadataRepository
import com.cacaosd.wowtelemetry.data.database.RoomSpellMetadataRepository
import com.cacaosd.wowtelemetry.data.database.createAppDatabase
import com.cacaosd.wowtelemetry.data.database.createCharacterProfileDatabase
import com.cacaosd.wowtelemetry.domain.CharacterProfileRepository
import com.cacaosd.wowtelemetry.domain.NpcMetadataRepository
import com.cacaosd.wowtelemetry.domain.SpellMetadataRepository
import org.koin.dsl.module

internal val databaseModule = module {
    single<CharacterProfileRepository> {
        RoomCharacterProfileRepository(get<CharacterProfileDatabase>())
    }
    single<SpellMetadataRepository> {
        RoomSpellMetadataRepository(get<AppDatabase>().spellNameDao(), get<AppDatabase>().spellDao())
    }
    single<NpcMetadataRepository> {
        RoomNpcMetadataRepository(get<AppDatabase>().creatureDao())
    }

    single<AppDatabase> { createAppDatabase(get()) }
    single<CharacterProfileDatabase> {
        createCharacterProfileDatabase(get())
    }
}
