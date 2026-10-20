package org.booklore.service.bookdrop;

import io.documentnode.epub4j.domain.Author;
import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.MediaTypes;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.epub.EpubWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Service
public class ZipToEpubConverterService {

    public Path convert(Path zipPath) throws IOException {
        List<TxtChapter> chapters = extractTxtChapters(zipPath);
        if (chapters.isEmpty()) {
            log.info("Zip contains no .txt files, skipping conversion: {}", zipPath);
            return null;
        }

        String baseName = stripExtension(zipPath.getFileName().toString());
        Path epubPath = zipPath.resolveSibling(baseName + ".epub");

        Book book = new Book();
        book.getMetadata().addTitle(baseName);
        book.getMetadata().addAuthor(new Author("Unknown"));

        for (TxtChapter chapter : chapters) {
            String html = wrapInHtml(chapter.title, chapter.body);
            String href = sanitizeHref(chapter.title) + ".html";
            Resource resource = new Resource(html.getBytes(StandardCharsets.UTF_8), href);
            resource.setMediaType(MediaTypes.XHTML);
            book.addSection(chapter.title, resource);
        }

        try (FileOutputStream fos = new FileOutputStream(epubPath.toFile())) {
            new EpubWriter().write(book, fos);
        }

        log.info("Converted {} -> {}", zipPath.getFileName(), epubPath.getFileName());
        return epubPath;
    }

    private List<TxtChapter> extractTxtChapters(Path zipPath) throws IOException {
        List<TxtChapter> chapters = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".txt")) {
                    String title = stripExtension(Path.of(entry.getName()).getFileName().toString());
                    try (InputStream is = zip.getInputStream(entry)) {
                        String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        chapters.add(new TxtChapter(title, body));
                    }
                }
            }
        }
        chapters.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        return chapters;
    }

    private String wrapInHtml(String title, String body) {
        String escaped = body
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\r\n", "\n")
                .replace("\n\n", "</p><p>")
                .replace("\n", "<br/>");
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>%s</title></head>
                <body><h1>%s</h1><p>%s</p></body>
                </html>
                """.formatted(title, title, escaped);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String sanitizeHref(String title) {
        return title.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private record TxtChapter(String title, String body) {}
}
