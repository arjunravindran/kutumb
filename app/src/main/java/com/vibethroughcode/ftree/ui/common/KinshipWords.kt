package com.vibethroughcode.ftree.ui.common

import androidx.annotation.StringRes
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.data.KinshipLanguage
import com.vibethroughcode.ftree.graph.HindiKinTerm

/*
 * The spelling of each family term in every language that has words. Generated from the same table as
 * res/values/kinship_<code>.xml, so a word and the resource it reads cannot drift apart.
 *
 * Which term applies is decided once, in graph/HindiKinship.kt; the enum keeps its Hindi names because
 * the structure it captures (side of family, who the line came back down through, who is older) is shared
 * by Hindi, Marathi, Bengali, Telugu and Tamil. A language that has one word where Hindi has two simply
 * points both terms at the same string.
 */

/** The string holding [this] term in [language], or null where the app has no words for it yet. */
@StringRes
@Suppress("CyclomaticComplexMethod", "LongMethod")
fun HindiKinTerm.termRes(language: KinshipLanguage): Int? = when (language) {
    KinshipLanguage.HINDI -> word().term
    KinshipLanguage.MARATHI -> mrTerm()
    KinshipLanguage.BENGALI -> bnTerm()
    KinshipLanguage.TELUGU -> teTerm()
    KinshipLanguage.TAMIL -> taTerm()
    else -> null
}

@StringRes
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun HindiKinTerm.mrTerm(): Int = when (this) {
    HindiKinTerm.SELF -> R.string.kin_mr_self
    HindiKinTerm.PITA -> R.string.kin_mr_pita
    HindiKinTerm.MATA -> R.string.kin_mr_mata
    HindiKinTerm.DADA -> R.string.kin_mr_dada
    HindiKinTerm.DADI -> R.string.kin_mr_dadi
    HindiKinTerm.NANA -> R.string.kin_mr_nana
    HindiKinTerm.NANI -> R.string.kin_mr_nani
    HindiKinTerm.PARDADA -> R.string.kin_mr_pardada
    HindiKinTerm.PARDADI -> R.string.kin_mr_pardadi
    HindiKinTerm.PARNANA -> R.string.kin_mr_parnana
    HindiKinTerm.PARNANI -> R.string.kin_mr_parnani
    HindiKinTerm.BETA -> R.string.kin_mr_beta
    HindiKinTerm.BETI -> R.string.kin_mr_beti
    HindiKinTerm.POTA -> R.string.kin_mr_pota
    HindiKinTerm.POTI -> R.string.kin_mr_poti
    HindiKinTerm.NATI -> R.string.kin_mr_nati
    HindiKinTerm.NATIN -> R.string.kin_mr_natin
    HindiKinTerm.PARPOTA -> R.string.kin_mr_parpota
    HindiKinTerm.PARPOTI -> R.string.kin_mr_parpoti
    HindiKinTerm.PARNATI -> R.string.kin_mr_parnati
    HindiKinTerm.PARNATIN -> R.string.kin_mr_parnatin
    HindiKinTerm.BHAI -> R.string.kin_mr_bhai
    HindiKinTerm.BEHEN -> R.string.kin_mr_behen
    HindiKinTerm.TAU -> R.string.kin_mr_tau
    HindiKinTerm.CHACHA -> R.string.kin_mr_chacha
    HindiKinTerm.FATHERS_BROTHER -> R.string.kin_mr_fathers_brother
    HindiKinTerm.TAI -> R.string.kin_mr_tai
    HindiKinTerm.CHACHI -> R.string.kin_mr_chachi
    HindiKinTerm.FATHERS_BROTHERS_WIFE -> R.string.kin_mr_fathers_brothers_wife
    HindiKinTerm.BUA -> R.string.kin_mr_bua
    HindiKinTerm.PHUPHA -> R.string.kin_mr_phupha
    HindiKinTerm.MAMA -> R.string.kin_mr_mama
    HindiKinTerm.MAMI -> R.string.kin_mr_mami
    HindiKinTerm.MAUSI -> R.string.kin_mr_mausi
    HindiKinTerm.MAUSA -> R.string.kin_mr_mausa
    HindiKinTerm.BHATIJA -> R.string.kin_mr_bhatija
    HindiKinTerm.BHATIJI -> R.string.kin_mr_bhatiji
    HindiKinTerm.BHANJA -> R.string.kin_mr_bhanja
    HindiKinTerm.BHANJI -> R.string.kin_mr_bhanji
    HindiKinTerm.CHACHERA_BHAI -> R.string.kin_mr_chachera_bhai
    HindiKinTerm.CHACHERI_BEHEN -> R.string.kin_mr_chacheri_behen
    HindiKinTerm.PHUPHERA_BHAI -> R.string.kin_mr_phuphera_bhai
    HindiKinTerm.PHUPHERI_BEHEN -> R.string.kin_mr_phupheri_behen
    HindiKinTerm.MAMERA_BHAI -> R.string.kin_mr_mamera_bhai
    HindiKinTerm.MAMERI_BEHEN -> R.string.kin_mr_mameri_behen
    HindiKinTerm.MAUSERA_BHAI -> R.string.kin_mr_mausera_bhai
    HindiKinTerm.MAUSERI_BEHEN -> R.string.kin_mr_mauseri_behen
    HindiKinTerm.PATI -> R.string.kin_mr_pati
    HindiKinTerm.PATNI -> R.string.kin_mr_patni
    HindiKinTerm.SASUR -> R.string.kin_mr_sasur
    HindiKinTerm.SAAS -> R.string.kin_mr_saas
    HindiKinTerm.JIJA -> R.string.kin_mr_jija
    HindiKinTerm.BHABHI -> R.string.kin_mr_bhabhi
    HindiKinTerm.BAHU -> R.string.kin_mr_bahu
    HindiKinTerm.DAMAD -> R.string.kin_mr_damad
    HindiKinTerm.SALA -> R.string.kin_mr_sala
    HindiKinTerm.SALI -> R.string.kin_mr_sali
    HindiKinTerm.JETH -> R.string.kin_mr_jeth
    HindiKinTerm.DEVAR -> R.string.kin_mr_devar
    HindiKinTerm.HUSBANDS_BROTHER -> R.string.kin_mr_husbands_brother
    HindiKinTerm.NANAD -> R.string.kin_mr_nanad
    HindiKinTerm.SAUTELA_PITA -> R.string.kin_mr_sautela_pita
    HindiKinTerm.SAUTELI_MATA -> R.string.kin_mr_sauteli_mata
    HindiKinTerm.SAUTELA_BETA -> R.string.kin_mr_sautela_beta
    HindiKinTerm.SAUTELI_BETI -> R.string.kin_mr_sauteli_beti
}

@StringRes
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun HindiKinTerm.bnTerm(): Int = when (this) {
    HindiKinTerm.SELF -> R.string.kin_bn_self
    HindiKinTerm.PITA -> R.string.kin_bn_pita
    HindiKinTerm.MATA -> R.string.kin_bn_mata
    HindiKinTerm.DADA -> R.string.kin_bn_dada
    HindiKinTerm.DADI -> R.string.kin_bn_dadi
    HindiKinTerm.NANA -> R.string.kin_bn_nana
    HindiKinTerm.NANI -> R.string.kin_bn_nani
    HindiKinTerm.PARDADA -> R.string.kin_bn_pardada
    HindiKinTerm.PARDADI -> R.string.kin_bn_pardadi
    HindiKinTerm.PARNANA -> R.string.kin_bn_parnana
    HindiKinTerm.PARNANI -> R.string.kin_bn_parnani
    HindiKinTerm.BETA -> R.string.kin_bn_beta
    HindiKinTerm.BETI -> R.string.kin_bn_beti
    HindiKinTerm.POTA -> R.string.kin_bn_pota
    HindiKinTerm.POTI -> R.string.kin_bn_poti
    HindiKinTerm.NATI -> R.string.kin_bn_nati
    HindiKinTerm.NATIN -> R.string.kin_bn_natin
    HindiKinTerm.PARPOTA -> R.string.kin_bn_parpota
    HindiKinTerm.PARPOTI -> R.string.kin_bn_parpoti
    HindiKinTerm.PARNATI -> R.string.kin_bn_parnati
    HindiKinTerm.PARNATIN -> R.string.kin_bn_parnatin
    HindiKinTerm.BHAI -> R.string.kin_bn_bhai
    HindiKinTerm.BEHEN -> R.string.kin_bn_behen
    HindiKinTerm.TAU -> R.string.kin_bn_tau
    HindiKinTerm.CHACHA -> R.string.kin_bn_chacha
    HindiKinTerm.FATHERS_BROTHER -> R.string.kin_bn_fathers_brother
    HindiKinTerm.TAI -> R.string.kin_bn_tai
    HindiKinTerm.CHACHI -> R.string.kin_bn_chachi
    HindiKinTerm.FATHERS_BROTHERS_WIFE -> R.string.kin_bn_fathers_brothers_wife
    HindiKinTerm.BUA -> R.string.kin_bn_bua
    HindiKinTerm.PHUPHA -> R.string.kin_bn_phupha
    HindiKinTerm.MAMA -> R.string.kin_bn_mama
    HindiKinTerm.MAMI -> R.string.kin_bn_mami
    HindiKinTerm.MAUSI -> R.string.kin_bn_mausi
    HindiKinTerm.MAUSA -> R.string.kin_bn_mausa
    HindiKinTerm.BHATIJA -> R.string.kin_bn_bhatija
    HindiKinTerm.BHATIJI -> R.string.kin_bn_bhatiji
    HindiKinTerm.BHANJA -> R.string.kin_bn_bhanja
    HindiKinTerm.BHANJI -> R.string.kin_bn_bhanji
    HindiKinTerm.CHACHERA_BHAI -> R.string.kin_bn_chachera_bhai
    HindiKinTerm.CHACHERI_BEHEN -> R.string.kin_bn_chacheri_behen
    HindiKinTerm.PHUPHERA_BHAI -> R.string.kin_bn_phuphera_bhai
    HindiKinTerm.PHUPHERI_BEHEN -> R.string.kin_bn_phupheri_behen
    HindiKinTerm.MAMERA_BHAI -> R.string.kin_bn_mamera_bhai
    HindiKinTerm.MAMERI_BEHEN -> R.string.kin_bn_mameri_behen
    HindiKinTerm.MAUSERA_BHAI -> R.string.kin_bn_mausera_bhai
    HindiKinTerm.MAUSERI_BEHEN -> R.string.kin_bn_mauseri_behen
    HindiKinTerm.PATI -> R.string.kin_bn_pati
    HindiKinTerm.PATNI -> R.string.kin_bn_patni
    HindiKinTerm.SASUR -> R.string.kin_bn_sasur
    HindiKinTerm.SAAS -> R.string.kin_bn_saas
    HindiKinTerm.JIJA -> R.string.kin_bn_jija
    HindiKinTerm.BHABHI -> R.string.kin_bn_bhabhi
    HindiKinTerm.BAHU -> R.string.kin_bn_bahu
    HindiKinTerm.DAMAD -> R.string.kin_bn_damad
    HindiKinTerm.SALA -> R.string.kin_bn_sala
    HindiKinTerm.SALI -> R.string.kin_bn_sali
    HindiKinTerm.JETH -> R.string.kin_bn_jeth
    HindiKinTerm.DEVAR -> R.string.kin_bn_devar
    HindiKinTerm.HUSBANDS_BROTHER -> R.string.kin_bn_husbands_brother
    HindiKinTerm.NANAD -> R.string.kin_bn_nanad
    HindiKinTerm.SAUTELA_PITA -> R.string.kin_bn_sautela_pita
    HindiKinTerm.SAUTELI_MATA -> R.string.kin_bn_sauteli_mata
    HindiKinTerm.SAUTELA_BETA -> R.string.kin_bn_sautela_beta
    HindiKinTerm.SAUTELI_BETI -> R.string.kin_bn_sauteli_beti
}

@StringRes
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun HindiKinTerm.teTerm(): Int = when (this) {
    HindiKinTerm.SELF -> R.string.kin_te_self
    HindiKinTerm.PITA -> R.string.kin_te_pita
    HindiKinTerm.MATA -> R.string.kin_te_mata
    HindiKinTerm.DADA -> R.string.kin_te_dada
    HindiKinTerm.DADI -> R.string.kin_te_dadi
    HindiKinTerm.NANA -> R.string.kin_te_nana
    HindiKinTerm.NANI -> R.string.kin_te_nani
    HindiKinTerm.PARDADA -> R.string.kin_te_pardada
    HindiKinTerm.PARDADI -> R.string.kin_te_pardadi
    HindiKinTerm.PARNANA -> R.string.kin_te_parnana
    HindiKinTerm.PARNANI -> R.string.kin_te_parnani
    HindiKinTerm.BETA -> R.string.kin_te_beta
    HindiKinTerm.BETI -> R.string.kin_te_beti
    HindiKinTerm.POTA -> R.string.kin_te_pota
    HindiKinTerm.POTI -> R.string.kin_te_poti
    HindiKinTerm.NATI -> R.string.kin_te_nati
    HindiKinTerm.NATIN -> R.string.kin_te_natin
    HindiKinTerm.PARPOTA -> R.string.kin_te_parpota
    HindiKinTerm.PARPOTI -> R.string.kin_te_parpoti
    HindiKinTerm.PARNATI -> R.string.kin_te_parnati
    HindiKinTerm.PARNATIN -> R.string.kin_te_parnatin
    HindiKinTerm.BHAI -> R.string.kin_te_bhai
    HindiKinTerm.BEHEN -> R.string.kin_te_behen
    HindiKinTerm.TAU -> R.string.kin_te_tau
    HindiKinTerm.CHACHA -> R.string.kin_te_chacha
    HindiKinTerm.FATHERS_BROTHER -> R.string.kin_te_fathers_brother
    HindiKinTerm.TAI -> R.string.kin_te_tai
    HindiKinTerm.CHACHI -> R.string.kin_te_chachi
    HindiKinTerm.FATHERS_BROTHERS_WIFE -> R.string.kin_te_fathers_brothers_wife
    HindiKinTerm.BUA -> R.string.kin_te_bua
    HindiKinTerm.PHUPHA -> R.string.kin_te_phupha
    HindiKinTerm.MAMA -> R.string.kin_te_mama
    HindiKinTerm.MAMI -> R.string.kin_te_mami
    HindiKinTerm.MAUSI -> R.string.kin_te_mausi
    HindiKinTerm.MAUSA -> R.string.kin_te_mausa
    HindiKinTerm.BHATIJA -> R.string.kin_te_bhatija
    HindiKinTerm.BHATIJI -> R.string.kin_te_bhatiji
    HindiKinTerm.BHANJA -> R.string.kin_te_bhanja
    HindiKinTerm.BHANJI -> R.string.kin_te_bhanji
    HindiKinTerm.CHACHERA_BHAI -> R.string.kin_te_chachera_bhai
    HindiKinTerm.CHACHERI_BEHEN -> R.string.kin_te_chacheri_behen
    HindiKinTerm.PHUPHERA_BHAI -> R.string.kin_te_phuphera_bhai
    HindiKinTerm.PHUPHERI_BEHEN -> R.string.kin_te_phupheri_behen
    HindiKinTerm.MAMERA_BHAI -> R.string.kin_te_mamera_bhai
    HindiKinTerm.MAMERI_BEHEN -> R.string.kin_te_mameri_behen
    HindiKinTerm.MAUSERA_BHAI -> R.string.kin_te_mausera_bhai
    HindiKinTerm.MAUSERI_BEHEN -> R.string.kin_te_mauseri_behen
    HindiKinTerm.PATI -> R.string.kin_te_pati
    HindiKinTerm.PATNI -> R.string.kin_te_patni
    HindiKinTerm.SASUR -> R.string.kin_te_sasur
    HindiKinTerm.SAAS -> R.string.kin_te_saas
    HindiKinTerm.JIJA -> R.string.kin_te_jija
    HindiKinTerm.BHABHI -> R.string.kin_te_bhabhi
    HindiKinTerm.BAHU -> R.string.kin_te_bahu
    HindiKinTerm.DAMAD -> R.string.kin_te_damad
    HindiKinTerm.SALA -> R.string.kin_te_sala
    HindiKinTerm.SALI -> R.string.kin_te_sali
    HindiKinTerm.JETH -> R.string.kin_te_jeth
    HindiKinTerm.DEVAR -> R.string.kin_te_devar
    HindiKinTerm.HUSBANDS_BROTHER -> R.string.kin_te_husbands_brother
    HindiKinTerm.NANAD -> R.string.kin_te_nanad
    HindiKinTerm.SAUTELA_PITA -> R.string.kin_te_sautela_pita
    HindiKinTerm.SAUTELI_MATA -> R.string.kin_te_sauteli_mata
    HindiKinTerm.SAUTELA_BETA -> R.string.kin_te_sautela_beta
    HindiKinTerm.SAUTELI_BETI -> R.string.kin_te_sauteli_beti
}

@StringRes
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun HindiKinTerm.taTerm(): Int = when (this) {
    HindiKinTerm.SELF -> R.string.kin_ta_self
    HindiKinTerm.PITA -> R.string.kin_ta_pita
    HindiKinTerm.MATA -> R.string.kin_ta_mata
    HindiKinTerm.DADA -> R.string.kin_ta_dada
    HindiKinTerm.DADI -> R.string.kin_ta_dadi
    HindiKinTerm.NANA -> R.string.kin_ta_nana
    HindiKinTerm.NANI -> R.string.kin_ta_nani
    HindiKinTerm.PARDADA -> R.string.kin_ta_pardada
    HindiKinTerm.PARDADI -> R.string.kin_ta_pardadi
    HindiKinTerm.PARNANA -> R.string.kin_ta_parnana
    HindiKinTerm.PARNANI -> R.string.kin_ta_parnani
    HindiKinTerm.BETA -> R.string.kin_ta_beta
    HindiKinTerm.BETI -> R.string.kin_ta_beti
    HindiKinTerm.POTA -> R.string.kin_ta_pota
    HindiKinTerm.POTI -> R.string.kin_ta_poti
    HindiKinTerm.NATI -> R.string.kin_ta_nati
    HindiKinTerm.NATIN -> R.string.kin_ta_natin
    HindiKinTerm.PARPOTA -> R.string.kin_ta_parpota
    HindiKinTerm.PARPOTI -> R.string.kin_ta_parpoti
    HindiKinTerm.PARNATI -> R.string.kin_ta_parnati
    HindiKinTerm.PARNATIN -> R.string.kin_ta_parnatin
    HindiKinTerm.BHAI -> R.string.kin_ta_bhai
    HindiKinTerm.BEHEN -> R.string.kin_ta_behen
    HindiKinTerm.TAU -> R.string.kin_ta_tau
    HindiKinTerm.CHACHA -> R.string.kin_ta_chacha
    HindiKinTerm.FATHERS_BROTHER -> R.string.kin_ta_fathers_brother
    HindiKinTerm.TAI -> R.string.kin_ta_tai
    HindiKinTerm.CHACHI -> R.string.kin_ta_chachi
    HindiKinTerm.FATHERS_BROTHERS_WIFE -> R.string.kin_ta_fathers_brothers_wife
    HindiKinTerm.BUA -> R.string.kin_ta_bua
    HindiKinTerm.PHUPHA -> R.string.kin_ta_phupha
    HindiKinTerm.MAMA -> R.string.kin_ta_mama
    HindiKinTerm.MAMI -> R.string.kin_ta_mami
    HindiKinTerm.MAUSI -> R.string.kin_ta_mausi
    HindiKinTerm.MAUSA -> R.string.kin_ta_mausa
    HindiKinTerm.BHATIJA -> R.string.kin_ta_bhatija
    HindiKinTerm.BHATIJI -> R.string.kin_ta_bhatiji
    HindiKinTerm.BHANJA -> R.string.kin_ta_bhanja
    HindiKinTerm.BHANJI -> R.string.kin_ta_bhanji
    HindiKinTerm.CHACHERA_BHAI -> R.string.kin_ta_chachera_bhai
    HindiKinTerm.CHACHERI_BEHEN -> R.string.kin_ta_chacheri_behen
    HindiKinTerm.PHUPHERA_BHAI -> R.string.kin_ta_phuphera_bhai
    HindiKinTerm.PHUPHERI_BEHEN -> R.string.kin_ta_phupheri_behen
    HindiKinTerm.MAMERA_BHAI -> R.string.kin_ta_mamera_bhai
    HindiKinTerm.MAMERI_BEHEN -> R.string.kin_ta_mameri_behen
    HindiKinTerm.MAUSERA_BHAI -> R.string.kin_ta_mausera_bhai
    HindiKinTerm.MAUSERI_BEHEN -> R.string.kin_ta_mauseri_behen
    HindiKinTerm.PATI -> R.string.kin_ta_pati
    HindiKinTerm.PATNI -> R.string.kin_ta_patni
    HindiKinTerm.SASUR -> R.string.kin_ta_sasur
    HindiKinTerm.SAAS -> R.string.kin_ta_saas
    HindiKinTerm.JIJA -> R.string.kin_ta_jija
    HindiKinTerm.BHABHI -> R.string.kin_ta_bhabhi
    HindiKinTerm.BAHU -> R.string.kin_ta_bahu
    HindiKinTerm.DAMAD -> R.string.kin_ta_damad
    HindiKinTerm.SALA -> R.string.kin_ta_sala
    HindiKinTerm.SALI -> R.string.kin_ta_sali
    HindiKinTerm.JETH -> R.string.kin_ta_jeth
    HindiKinTerm.DEVAR -> R.string.kin_ta_devar
    HindiKinTerm.HUSBANDS_BROTHER -> R.string.kin_ta_husbands_brother
    HindiKinTerm.NANAD -> R.string.kin_ta_nanad
    HindiKinTerm.SAUTELA_PITA -> R.string.kin_ta_sautela_pita
    HindiKinTerm.SAUTELI_MATA -> R.string.kin_ta_sauteli_mata
    HindiKinTerm.SAUTELA_BETA -> R.string.kin_ta_sautela_beta
    HindiKinTerm.SAUTELI_BETI -> R.string.kin_ta_sauteli_beti
}
