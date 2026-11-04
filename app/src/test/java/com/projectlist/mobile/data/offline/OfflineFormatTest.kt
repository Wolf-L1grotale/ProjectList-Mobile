package com.projectlist.mobile.data.offline

import java.util.Base64
import java.util.Comparator
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineFormatTest {

    private fun sample(n: Int): ByteArray = ByteArray(n) { ((it * 37 + 11) and 0xff).toByte() }

    @Test
    fun xxhash64MatchesReference() {
        assertEquals("ef46db3751d8e999", XxHash64.hash(ByteArray(0)).toString(16))
        assertEquals("44bc2cf5ad770999", XxHash64.hash("abc".toByteArray()).toString(16))
        assertEquals("128da10cfbdc59d9", XxHash64.hash(sample(1000)).toString(16))    }

    @Test
    fun highwayHashMatchesReference() {
        val expected = mapOf(
            0 to "5e76d207cf4ab20866fdc03c83e8a0f4e8f458e880777956ec0bae4e9f23f6c5",
            1 to "174aba48e72ea23bae1fb80353e2c4c095279ad8971b9a2472427b07c9631af3",
            2 to "bf8f9170e133404942a28386cf5a39b2ce70f6bdbf9defc2e33b9929ded0de01",
            3 to "7ae0e545d9842aa4873ec9f3b115c64778a65f1556d0bd85f8e3df9e5e6cacb2",
            4 to "33365188777a629dd09a61e0928e31d8d7fd9571dc0ccd400224293abb67835d",
            5 to "8e4c3e5e3b2e1f0f9b28cc05188a5e7040c9ddc19445620486e2b88f09641045",
            15 to "b90022458d4ea70363698decd42a6d4cf0ac1760b709c4c8cc3cfc8e58b3f89c",
            16 to "fcbff0fa16f3fc4607d048e8190d22ca6c896eb900df838f060f6e9d6d394d22",
            17 to "798d2c9ba0cdff5c952f436190219d0a2159a975d560165728025bc89854a67f",
            31 to "1a94e831232868be84dcc9098a565df1974eeaa7a6bce039a8cba37d317559f6",
            32 to "0e83580e6e5c9ff47ec6d636377bf7b2b58f09eddded3fedd46a2f76b0ef1e1d",
            33 to "9c403e123cb53576fc9c34bf1ecca7311b7ff087e39defb48c0c05eabf95c45c",
            63 to "cdb5ed475cce5c8841449bf92869854c7a9a53e714fb78d200a7db93539ed65f",
            64 to "d6c9c97ee2db53e8ab6a7a82a3b9951806e52fa83f6f3e16c0fb72656a896779",
            65 to "fb5d9a0cecec4c12f6d89b83abb1f5ef7faae26522b24ea88edb311c99e584a1",
            100 to "6dc66d6bd78a8c54985de956dbd617a4bb9442c391067ce508bbb1dfff8f9432",
            255 to "856b7a948a220bfa4f6261fcd60dc9c89a19d30fec098539c64bcee118a69f0c",
            256 to "8c5037bdfe841544aed5c25e2e7f2673ad4d484b1ea3235c29b227e9241dfb13",
            257 to "73a4fea644ef1fbb6ecfb235df580e466d96d52a5bc43ccbaf172dfbfcb4b6a5",
            1_000_000 to "3b6fa01de003c3f71809ff751900a3f1f3387cdf3c131a79d39fe97f7f8af0d1",
        )
        for ((size, hex) in expected) {
            assertEquals("размер $size", hex, highwayHash256(sample(size)).toHex())
        }
        assertEquals(
            "5a26e667b2ceff3c7a5ed30a9258ed2d5a996f328cff13c9f3f7a040676034f5",
            highwayHash256("abc".toByteArray()).toHex(),
        )
    }

    @Test
    fun highwayHashStreamingMatchesOneShot() {
        val data = sample(200_000)
        val streaming = HighwayHash256().apply {
            var offset = 0
            while (offset < data.size) {
                val length = minOf(7919, data.size - offset)
                update(data.copyOfRange(offset, offset + length))
                offset += length
            }
        }.digest()
        assertArrayEquals(highwayHash256(data), streaming)
    }

    @Test
    fun msgpackRoundTrip() {
        val packed = MsgWriter()
            .map(4)
            .str("nil").nil()
            .str("num").int(-1234567)
            .str("bin").bin(byteArrayOf(1, 2, 3))
            .str("arr").array(2).str("x").int(42)
            .toByteArray()

        val map = MsgReader(packed).readMapValue()
        assertTrue(map["nil"] is MsgValue.Nil)
        assertEquals(-1234567L, map["num"]?.asLong())
        assertArrayEquals(byteArrayOf(1, 2, 3), map["bin"]?.asBytesOrNull())
        val arr = map["arr"]?.asListOrNull()
        assertEquals("x", arr?.getOrNull(0)?.asStringOrNull())
        assertEquals(42L, arr?.getOrNull(1)?.asLong())
    }

    @Test
    fun bitrotRoundTrip() {
        for (size in intArrayOf(0, 1, 1024, XlMeta.SHARD_SIZE, XlMeta.SHARD_SIZE + 1, 3 * XlMeta.SHARD_SIZE + 777)) {
            val data = sample(size)
            val packed = XlMeta.withBitrot(data)
            assertArrayEquals("размер $size", data, XlMeta.stripBitrot(packed, size.toLong()))
        }
    }

    @Test
    fun xlMetaRoundTrip() {
        val data = sample(3 * XlMeta.SHARD_SIZE + 123)
        val ddir = sample(16)
        val meta = XlMeta.build(data, "application/octet-stream", ddir, 1_700_000_000_000_000_000L)

        val parsed = XlMeta.parse(meta)
        assertFalse(parsed.obj.inline)
        assertEquals(data.size.toLong(), parsed.obj.size)
        assertEquals(md5Hex(data), parsed.obj.etag)
        assertEquals("application/octet-stream", parsed.obj.contentType)
        assertEquals(XlMeta.formatDdir(ddir), parsed.obj.ddirUuid)
        assertEquals(1_700_000_000_000_000_000L, parsed.obj.mtime)
        assertEquals(36, parsed.obj.ddirUuid?.length)
    }

    @Test
    fun parsesRealMinioInlineObject() {
        val stream = javaClass.classLoader?.getResourceAsStream("real-index.xl.meta")
        assertNotNull("нет тестового файла real-index.xl.meta", stream)
        val bytes = stream!!.use { it.readBytes() }
        val parsed = XlMeta.parse(bytes)
        assertTrue(parsed.obj.inline)
        assertEquals(511L, parsed.obj.size)
        assertEquals("217f13d4f9ced8fae3081d0df097360d", parsed.obj.etag)

        val data = parsed.inlineData()
        assertNotNull(data)
        val text = String(data!!, Charsets.UTF_8)
        assertTrue(text.startsWith("{"))
        assertEquals(parsed.obj.size, data.size.toLong())
    }

    @Test
    fun cryptoRecordRoundTrip() {
        val key = sample(32)
        val payload = """{"id":"test","name":"Проект","data":{"version":"1.0.0"}}"""
        val encrypted = CryptoBox.encryptRecord(payload, key)
        assertEquals(payload, CryptoBox.decryptRecord(encrypted, key))
        assertEquals(CryptoBox.keyFingerprint(key), sha256Hex(key).take(16))
    }

    @Test
    fun cryptoBlobRoundTrip() {
        val key = sample(32)
        val data = sample(500_000)
        val encrypted = CryptoBox.encryptBlob(data, key)
        assertFalse(encrypted.contentEquals(data))
        assertArrayEquals(data, CryptoBox.decryptBlob(encrypted, key))
    }

    @Test
    fun parsesHexAndBase64Keys() {
        val key = sample(32)
        assertArrayEquals(key, CryptoBox.parseKey(key.toHex()))
        assertArrayEquals(key, CryptoBox.parseKey(Base64.getEncoder().encodeToString(key)))
    }

    @Test
    fun minioDiskStoreWritesNonInlineObjects() {
        val root = Files.createTempDirectory("pl-mobile-minio")
        try {
            val store = MinioDiskStore(LocalDiskFs(root), "secure-bucket")
            val key = "projects/abc.enc"
            val first = sample(2 * XlMeta.SHARD_SIZE + 17)

            store.put(key, first, "application/json")
            assertArrayEquals(first, store.get(key))

            val metaPath = root.resolve("secure-bucket/$key/xl.meta")
            assertTrue("нет xl.meta", Files.exists(metaPath))
            val meta = XlMeta.parse(Files.readAllBytes(metaPath))
            assertFalse("объект должен быть non-inline", meta.obj.inline)
            assertEquals(first.size.toLong(), meta.obj.size)
            assertEquals("application/json", meta.obj.contentType)

            // Перезапись меньшим объектом: старый каталог данных должен исчезнуть.
            val second = sample(1234)
            store.put(key, second, "application/json")
            assertArrayEquals(second, store.get(key))
            val dirs = Files.list(root.resolve("secure-bucket/$key")).use { stream ->
                stream.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList()
            }
            assertEquals(1, dirs.size)
            assertEquals("projects/abc.enc", store.listKeys("projects").single())

            store.delete(key)
            assertNull(store.get(key))
            assertFalse(Files.exists(root.resolve("secure-bucket/$key")))
        } finally {
            deleteTree(root)
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private class LocalDiskFs(private val root: Path) : DiskFs {
        private fun resolve(path: String): Path {
            val clean = normalizeDir(path)
            return if (clean.isEmpty()) root else root.resolve(clean)
        }

        override fun exists(path: String): Boolean = Files.exists(resolve(path))

        override fun isDirectory(path: String): Boolean = Files.isDirectory(resolve(path))

        override fun list(path: String): List<DiskEntry> =
            Files.list(resolve(path)).use { stream ->
                stream.map { item ->
                    DiskEntry(
                        name = item.fileName.toString(),
                        directory = Files.isDirectory(item),
                        size = if (Files.isRegularFile(item)) Files.size(item) else 0L,
                    )
                }.toList()
            }

        override fun read(path: String): ByteArray = Files.readAllBytes(resolve(path))

        override fun write(path: String, data: ByteArray) {
            val target = resolve(path)
            target.parent?.let { Files.createDirectories(it) }
            Files.write(target, data)
        }

        override fun mkdirs(path: String) {
            Files.createDirectories(resolve(path))
        }

        override fun delete(path: String) {
            Files.deleteIfExists(resolve(path))
        }

        override fun deleteTree(path: String) {
            val target = resolve(path)
            if (Files.isDirectory(target)) {
                Files.walk(target).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            } else {
                Files.deleteIfExists(target)
            }
        }

        override fun close() = Unit
    }
}
