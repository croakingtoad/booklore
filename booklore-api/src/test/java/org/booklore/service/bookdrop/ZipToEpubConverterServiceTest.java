package org.booklore.service.bookdrop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ZipToEpubConverterServiceTest {

    private final ZipToEpubConverterService converter = new ZipToEpubConverterService();

    @TempDir
    Path tempDir;

    @Test
    void converts_txt_entries_into_epub_sections() throws IOException {
        Path zip = writeZip("book.zip", entry("chapter-a.txt", "First body."), entry("chapter-b.txt", "Second body."));

        Path epub = converter.convert(zip);

        assertNotNull(epub);
        List<String> chapters = chapterDocuments(epub);
        assertEquals(2, chapters.size());
        assertTrue(chapters.stream().anyMatch(c -> c.contains("First body.")));
        assertTrue(chapters.stream().anyMatch(c -> c.contains("Second body.")));
    }

    @Test
    void returns_null_when_zip_has_no_txt_entries() throws IOException {
        Path zip = writeZip("images.zip", entry("cover.png", "not text"));

        assertNull(converter.convert(zip));
    }

    @Test
    void titles_with_xml_metacharacters_produce_well_formed_xhtml() throws IOException {
        Path zip = writeZip("meta.zip", entry("R&D notes <draft>.txt", "Body."));

        Path epub = converter.convert(zip);

        String chapter = chapterDocuments(epub).getFirst();
        assertDoesNotThrow(() -> DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(new ByteArrayInputStream(chapter.getBytes(StandardCharsets.UTF_8))),
                "chapter XHTML must stay well-formed when the filename contains & or <");
        assertTrue(chapter.contains("R&amp;D notes &lt;draft&gt;"));
    }

    @Test
    void entry_names_that_sanitize_to_the_same_href_both_survive() throws IOException {
        Path zip = writeZip("collide.zip", entry("ch 1.txt", "SPACE body."), entry("ch#1.txt", "HASH body."));

        Path epub = converter.convert(zip);

        List<String> chapters = chapterDocuments(epub);
        assertEquals(2, chapters.size(), "neither chapter may be dropped by an href collision");
        assertTrue(chapters.stream().anyMatch(c -> c.contains("SPACE body.")));
        assertTrue(chapters.stream().anyMatch(c -> c.contains("HASH body.")));
    }

    private record Entry(String name, String content) {}

    private Entry entry(String name, String content) {
        return new Entry(name, content);
    }

    private Path writeZip(String zipName, Entry... entries) throws IOException {
        Path zip = tempDir.resolve(zipName);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Entry e : entries) {
                out.putNextEntry(new ZipEntry(e.name()));
                out.write(e.content().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    /** Returns the XHTML chapter documents inside the generated epub. */
    private List<String> chapterDocuments(Path epub) throws IOException {
        List<String> documents = new ArrayList<>();
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(epub.toFile())) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".html")) {
                    documents.add(new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return documents;
    }
}
