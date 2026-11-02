package com.rewatt.app.security

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registered people and their face samples, stored as files (not Room: the database uses destructive
 * migration, which would wipe registered faces on the next schema bump).
 * Layout: filesDir/security/family/people.json (names) + <id>.emb (see [FaceSampleCodec]) + <id>.jpg
 * (112×112 aligned thumbnail). Samples saved before named people (v1) belong to [LEGACY_PERSON_ID].
 */
class FamilyFaceStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "security/family").apply { mkdirs() }
    private val peopleFile = File(dir, PEOPLE_FILE)
    private val mutableSamples = MutableStateFlow(loadSamples())
    private val mutablePeople = MutableStateFlow(loadPeople(mutableSamples.value))
    val samples: StateFlow<List<FaceSample>> = mutableSamples.asStateFlow()
    val people: StateFlow<List<Person>> = mutablePeople.asStateFlow()

    fun nameOf(personId: String): String? = mutablePeople.value.firstOrNull { it.id == personId }?.name

    @Synchronized
    fun addPerson(name: String): Person {
        val person = Person(UUID.randomUUID().toString().take(12), name.trim(), System.currentTimeMillis())
        savePeople(mutablePeople.value + person)
        return person
    }

    @Synchronized
    fun rename(personId: String, name: String) {
        savePeople(mutablePeople.value.map { if (it.id == personId) it.copy(name = name.trim()) else it })
    }

    @Synchronized
    fun add(personId: String, embedding: FloatArray, thumbnail: Bitmap): FaceSample {
        val id = UUID.randomUUID().toString().take(12)
        val createdAt = System.currentTimeMillis()
        val thumbFile = File(dir, "$id.jpg")
        thumbFile.outputStream().use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        DataOutputStream(File(dir, "$id.emb").outputStream().buffered()).use { out ->
            FaceSampleCodec.write(out, personId, createdAt, embedding)
        }
        val sample = FaceSample(id, personId, createdAt, embedding, thumbFile.absolutePath)
        mutableSamples.value = (mutableSamples.value + sample).sortedBy { it.createdAtMillis }
        return sample
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.emb").delete()
        File(dir, "$id.jpg").delete()
        mutableSamples.value = mutableSamples.value.filterNot { it.id == id }
    }

    @Synchronized
    fun deletePerson(personId: String) {
        mutableSamples.value.filter { it.personId == personId }.forEach { delete(it.id) }
        savePeople(mutablePeople.value.filterNot { it.id == personId })
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        mutableSamples.value = emptyList()
        mutablePeople.value = emptyList()
    }

    private fun savePeople(people: List<Person>) {
        writePeopleFile(people)
        mutablePeople.value = people.sortedBy { it.createdAtMillis }
    }

    private fun writePeopleFile(people: List<Person>) {
        val json = JSONArray()
        people.forEach { json.put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAtMillis)) }
        peopleFile.writeText(JSONObject().put("people", json).toString())
    }

    private fun loadSamples(): List<FaceSample> =
        dir.listFiles { file -> file.extension == "emb" }.orEmpty().mapNotNull { file ->
            runCatching {
                val decoded = DataInputStream(file.inputStream().buffered()).use { FaceSampleCodec.read(it) }
                val id = file.nameWithoutExtension
                FaceSample(
                    id = id,
                    personId = decoded.personId ?: LEGACY_PERSON_ID,
                    createdAtMillis = decoded.createdAtMillis,
                    embedding = decoded.embedding,
                    thumbnailPath = File(dir, "$id.jpg").absolutePath,
                )
            }.onFailure { Log.w(TAG, "Skipping unreadable face sample ${file.name}", it) }.getOrNull()
        }.sortedBy { it.createdAtMillis }

    /** Reads people.json; v1 samples (or samples whose person is missing) get a "Family" person. */
    private fun loadPeople(samples: List<FaceSample>): List<Person> {
        val stored = runCatching {
            val array = JSONObject(peopleFile.readText()).getJSONArray("people")
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Person(o.getString("id"), o.getString("name"), o.optLong("createdAt"))
            }
        }.getOrDefault(emptyList())
        val known = stored.map { it.id }.toSet()
        val orphans = samples.filter { it.personId !in known }.map { it.personId }.distinct()
        if (orphans.isEmpty()) return stored.sortedBy { it.createdAtMillis }
        val recovered = orphans.map { id ->
            val first = samples.filter { it.personId == id }.minOf { it.createdAtMillis }
            Person(id, if (id == LEGACY_PERSON_ID) LEGACY_NAME else UNNAMED, first)
        }
        Log.i(TAG, "Recovered ${recovered.size} person(s) for samples without a name")
        val all = (stored + recovered).sortedBy { it.createdAtMillis }
        runCatching { writePeopleFile(all) }.onFailure { Log.w(TAG, "Could not save people.json", it) }
        return all
    }

    companion object {
        private const val TAG = "FamilyFaceStore"
        private const val PEOPLE_FILE = "people.json"
        const val LEGACY_PERSON_ID = "family"
        private const val LEGACY_NAME = "Family"
        private const val UNNAMED = "Unnamed"
        const val FAMILY_LABEL = "FAMILY"
    }
}
