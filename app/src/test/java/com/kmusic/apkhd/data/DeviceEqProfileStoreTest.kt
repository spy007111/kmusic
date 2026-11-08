package com.kmusic.apkhd.data

import com.kmusic.apkhd.playback.DeviceEqProfilePolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeviceEqProfileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.root, "profiles.json")
    private fun profile(id: String = "one", binding: String? = null) = DeviceEqProfile(id = id, name = "User chosen name",
        bindingKey = binding, eqEnabled = true, bandsMb = List(10) { it * 100 - 500 }, bass = 200,
        fxLoudness = 300, preGainDb = -3f, truePeakEnabled = true, ceilingDbTp = -1f)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }

    @Test fun serializationRoundTripIncludesAllPreferencesAndAutoState() {
        val p = profile()
        val document = DeviceEqProfileStore.Document(autoEnabled = true, profiles = listOf(p))
        assertEquals(document, DeviceEqProfileStore.decode(DeviceEqProfileStore.encode(document)))
    }

    @Test fun privateBindingPersistsOnlyHashNeverRawAddress() = runBlocking {
        val raw = "AA:BB:CC:DD:EE:FF"
        val key = DeviceEqProfilePolicy.bindingKey(DeviceEqProfilePolicy.Category.BLUETOOTH, raw, true)
        val file = file()
        DeviceEqProfileStore(file).update(profile(binding = key))
        val text = file.readText()
        assertTrue(text.contains(key!!))
        assertFalse(text.contains(raw))
        assertFalse(text.contains("systemVolume"))
        assertFalse(text.contains("autoPlay"))
        assertEquals(key, DeviceEqProfileStore(file).get("one")!!.bindingKey)
    }

    @Test fun persistedFlowAndManualProfileWorkWithoutPhysicalDevice() = runBlocking {
        val store = DeviceEqProfileStore(file())
        assertFalse(store.autoEnabled.first())
        assertTrue(store.profiles.first().isEmpty())
        store.update(profile())
        store.setAutoEnabled(true)
        val reloaded = DeviceEqProfileStore(file())
        assertTrue(reloaded.autoEnabled.first())
        assertEquals(profile(), reloaded.profiles.first().single())
        assertNull(reloaded.get("one")!!.bindingKey)
        reloaded.remove("one")
        assertTrue(reloaded.all().isEmpty())
    }

    @Test fun sameNamesDoNotAliasDifferentBindingsAndBindingTransferPreservesManualProfile() = runBlocking {
        val store = DeviceEqProfileStore(file())
        val speaker = DeviceEqProfilePolicy.bindingKey(DeviceEqProfilePolicy.Category.SPEAKER)!!
        val wired = DeviceEqProfilePolicy.bindingKey(DeviceEqProfilePolicy.Category.WIRED)!!
        store.update(profile("a", speaker))
        store.update(profile("b", wired))
        assertEquals("a", store.getForBinding(speaker)!!.id)
        assertEquals("b", store.getForBinding(wired)!!.id)
        store.update(profile("c", speaker))
        assertEquals("c", store.getForBinding(speaker)!!.id)
        assertNull(store.get("a")!!.bindingKey)
        assertEquals(3, store.all().size)
    }

    @Test fun updateAndReadsDefensivelyCopyMutableBandLists() = runBlocking {
        val bands = MutableList(10) { 100 }
        val store = DeviceEqProfileStore(file())
        store.update(profile().copy(bandsMb = bands))
        bands[0] = 1400
        assertEquals(100, store.get("one")!!.bandsMb[0])
        val read = store.profiles.first().single().bandsMb
        try { (read as MutableList<Int>)[0] = 1200; fail("Read should be immutable") } catch (_: UnsupportedOperationException) { }
        assertEquals(100, store.get("one")!!.bandsMb[0])
    }

    @Test fun validatesFieldBoundsAndRejectsNonFiniteValuesOrRawBindings() {
        val p = profile()
        listOf(
            p.copy(bandsMb = List(9) { 0 }), p.copy(bandsMb = List(10) { 1501 }),
            p.copy(bass = -1), p.copy(bass = 1001), p.copy(fxLoudness = 1501),
            p.copy(preGainDb = 0.1f), p.copy(preGainDb = -24.1f), p.copy(preGainDb = Float.NaN),
            p.copy(ceilingDbTp = 0.1f), p.copy(ceilingDbTp = -12.1f), p.copy(ceilingDbTp = Float.NEGATIVE_INFINITY),
            p.copy(name = ""), p.copy(name = "Bad\nname"), p.copy(id = "path/identity"),
            p.copy(bindingKey = "AA:BB:CC:DD:EE:FF")
        ).forEach { invalid -> rejects { invalid.validated() } }
        p.copy(bandsMb = List(10) { -1500 }, bass = 1000, fxLoudness = 1500, preGainDb = -24f, ceilingDbTp = -12f).validated()
    }

    @Test fun unknownSchemaPreservesOriginalBacksUpAndBlocksWrites() = runBlocking {
        val file = file().apply { writeText("{\"schemaVersion\":42,\"future\":\"keep-me\"}") }
        val original = file.readBytes()
        val store = DeviceEqProfileStore(file)
        assertEquals(DeviceEqProfileStore.LoadStatus.UNKNOWN_SCHEMA, store.load())
        assertTrue(store.all().isEmpty())
        assertArrayEquals(original, file.readBytes())
        val backup = temporary.root.listFiles()!!.single { it.name.endsWith(".bak") }
        assertArrayEquals(original, backup.readBytes())
        try { store.update(profile()); fail("Unknown schema must not be overwritten") } catch (_: IllegalStateException) { }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun damagedFileRemainsIntactWithSafeBackupAndNoSilentReset() = runBlocking {
        val file = file().apply { writeText("{broken json") }
        val original = file.readBytes()
        val store = DeviceEqProfileStore(file)
        assertEquals(DeviceEqProfileStore.LoadStatus.DAMAGED, store.load())
        assertArrayEquals(original, file.readBytes())
        assertEquals(1, temporary.root.listFiles()!!.count { it.name.endsWith(".bak") })
        assertEquals(DeviceEqProfileStore.LoadStatus.DAMAGED, store.load())
        assertEquals(1, temporary.root.listFiles()!!.count { it.name.endsWith(".bak") })
        try { store.setAutoEnabled(true); fail("Damaged file must not be overwritten") } catch (_: IllegalStateException) { }
    }

    @Test fun invalidOnDiskBoundsOrDuplicateIdsRejectEntireDocument() {
        val p = profile()
        rejects { DeviceEqProfileStore.decode(DeviceEqProfileStore.encode(DeviceEqProfileStore.Document(profiles = listOf(p, p)))) }
        rejects { DeviceEqProfileStore.decode(DeviceEqProfileStore.encode(DeviceEqProfileStore.Document(profiles = listOf(p.copy(bass = 5000))))) }
    }

    @Test fun atomicPatchesMergeIndependentFieldsFromLatestSavedProfile() = runBlocking {
        val store = DeviceEqProfileStore(file())
        store.update(profile().copy(truePeakEnabled = false, ceilingDbTp = -1f, bass = 0))
        listOf(
            async { store.updateExisting("one") { it.copy(truePeakEnabled = true) } },
            async { store.updateExisting("one") { it.copy(ceilingDbTp = -3f) } },
            async { store.updateExisting("one") { it.copy(bass = 800) } }
        ).awaitAll()
        val saved = store.get("one")!!
        assertTrue(saved.truePeakEnabled)
        assertEquals(-3f, saved.ceilingDbTp, 0f)
        assertEquals(800, saved.bass)
        assertEquals(saved, DeviceEqProfileStore(file()).get("one"))
    }

    @Test fun patchPreservesIdAndTransfersBindingWithoutDeletingOtherProfile() = runBlocking {
        val store = DeviceEqProfileStore(file())
        val speaker = DeviceEqProfilePolicy.bindingKey(DeviceEqProfilePolicy.Category.SPEAKER)!!
        val wired = DeviceEqProfilePolicy.bindingKey(DeviceEqProfilePolicy.Category.WIRED)!!
        store.update(profile("a", speaker))
        store.update(profile("b", wired))
        store.updateExisting("b") { it.copy(bindingKey = speaker) }
        assertEquals("b", store.getForBinding(speaker)!!.id)
        assertNull(store.get("a")!!.bindingKey)
        assertEquals(2, store.all().size)
        assertNull(store.getForBinding(wired))
    }

    @Test fun invalidMissingOrThrowingPatchLeavesOriginalMemoryAndFileUnchanged() = runBlocking {
        val file = file()
        val store = DeviceEqProfileStore(file)
        store.update(profile())
        val original = file.readBytes()
        val invalidPatches: List<(DeviceEqProfile) -> DeviceEqProfile> = listOf(
            { it.copy(id = "replacement") },
            { it.copy(bass = 1001) },
            { throw IllegalArgumentException("Rejected edit") }
        )
        invalidPatches.forEach { patch ->
            try { store.updateExisting("one", patch); fail("Expected patch rejection") } catch (_: IllegalArgumentException) { }
            assertEquals(profile(), store.get("one"))
            assertArrayEquals(original, file.readBytes())
        }
        try { store.updateExisting("missing") { it.copy(bass = 100) }; fail("Missing profile must not be recreated") }
        catch (_: NoSuchElementException) { }
        assertEquals(listOf(profile()), store.all())
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun mutexSerializesConcurrentUpdatesAndFileIsReadableAfterAtomicWrites() = runBlocking {
        val file = file()
        val store = DeviceEqProfileStore(file)
        (1..20).map { index -> async { store.update(profile("p$index")) } }.awaitAll()
        assertEquals(20, store.all().size)
        assertEquals(20, DeviceEqProfileStore(file).all().size)
        assertFalse(temporary.root.listFiles()!!.any { it.name.endsWith(".tmp") })
    }
}
