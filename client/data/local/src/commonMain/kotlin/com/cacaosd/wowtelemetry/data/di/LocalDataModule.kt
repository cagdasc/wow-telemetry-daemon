package com.cacaosd.wowtelemetry.data.di

import com.cacaosd.wowtelemetry.data.database.*
import com.cacaosd.wowtelemetry.domain.CharacterProfileRepository
import com.cacaosd.wowtelemetry.domain.NpcMetadataRepository
import com.cacaosd.wowtelemetry.domain.SpellMetadataRepository
import org.koin.dsl.module

fun localDataModule() = module {
    single<CharacterProfileRepository> {
        RoomCharacterProfileRepository(get<CharacterProfileDatabase>())
    }
    single<SpellMetadataRepository> {
        RoomSpellMetadataRepository(get<AppDatabase>().spellNameDao(), get<AppDatabase>().spellDao())
    }
    single<NpcMetadataRepository> {
        RoomNpcMetadataRepository(get<AppDatabase>().creatureDao())
    }
}
