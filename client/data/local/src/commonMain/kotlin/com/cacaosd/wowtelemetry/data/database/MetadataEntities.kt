package com.cacaosd.wowtelemetry.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Spell")
data class Spell(
    @PrimaryKey
    @ColumnInfo(name = "ID")
    val id: Long,
    @ColumnInfo(name = "NameSubtext_lang")
    val nameSubtextLang: String? = null,
    @ColumnInfo(name = "Description_lang")
    val descriptionLang: String? = null,
    @ColumnInfo(name = "AuraDescription_lang")
    val auraDescriptionLang: String? = null,
)

@Entity(tableName = "SpellName")
data class SpellName(
    @PrimaryKey
    @ColumnInfo(name = "ID")
    val id: Long,
    @ColumnInfo(name = "Name_lang")
    val name: String? = null,
)

@Entity(tableName = "Creature")
data class Creature(
    @PrimaryKey
    @ColumnInfo(name = "ID")
    val id: Int,

    @ColumnInfo(name = "Name_lang")
    val nameLang: String?,

    @ColumnInfo(name = "Classification")
    val classification: Int? = null,

    @ColumnInfo(name = "CreatureFamily")
    val creatureFamily: Int? = null,

    @ColumnInfo(name = "CreatureType")
    val creatureType: Int? = null,

    @ColumnInfo(name = "NameAlt_lang")
    val nameAltLang: String?,

    @ColumnInfo(name = "Title_lang")
    val titleLang: String?,

    @ColumnInfo(name = "TitleAlt_lang")
    val titleAltLang: String?,

    @ColumnInfo(name = "StartAnimState")
    val startAnimState: Int?,

    @ColumnInfo(name = "AlwaysItem_0")
    val alwaysItem0: Int?,

    @ColumnInfo(name = "AlwaysItem_1")
    val alwaysItem1: Int?,

    @ColumnInfo(name = "AlwaysItem_2")
    val alwaysItem2: Int?,

    @ColumnInfo(name = "DisplayID_0")
    val displayId0: Int?,

    @ColumnInfo(name = "DisplayID_1")
    val displayId1: Int?,

    @ColumnInfo(name = "DisplayID_2")
    val displayId2: Int?,

    @ColumnInfo(name = "DisplayID_3")
    val displayId3: Int?,

    @ColumnInfo(name = "DisplayProbability_0")
    val displayProbability0: Int?,

    @ColumnInfo(name = "DisplayProbability_1")
    val displayProbability1: Int?,

    @ColumnInfo(name = "DisplayProbability_2")
    val displayProbability2: Int?,

    @ColumnInfo(name = "DisplayProbability_3")
    val displayProbability3: Int?
)
