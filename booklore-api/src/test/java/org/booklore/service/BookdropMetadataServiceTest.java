package org.booklore.service;

import org.booklore.exception.APIException;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookdropFileEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.bookdrop.BookdropMetadataHelper;
import org.booklore.service.bookdrop.BookdropMetadataService;
import org.booklore.service.bookdrop.FilenamePatternExtractor;
import org.booklore.service.metadata.MetadataRefreshService;
import org.booklore.service.metadata.extractor.CbxMetadataExtractor;
import org.booklore.service.metadata.extractor.EpubMetadataExtractor;
import org.booklore.service.metadata.extractor.MetadataExtractorFactory;
import org.booklore.service.metadata.extractor.PdfMetadataExtractor;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.booklore.model.entity.BookdropFileEntity.Status.PENDING_REVIEW;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookdropMetadataServiceTest {

    @Mock
    private BookdropFileRepository bookdropFileRepository;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private EpubMetadataExtractor epubMetadataExtractor;
    @Mock
    private PdfMetadataExtractor pdfMetadataExtractor;
    @Mock
    private CbxMetadataExtractor cbxMetadataExtractor;
    @Mock
    private MetadataRefreshService metadataRefreshService;
    @Mock
    private FileService fileService;
    @Mock
    private MetadataExtractorFactory metadataExtractorFactory;
    @Mock
    private FilenamePatternExtractor filenamePatternExtractor;
    @Mock
    private BookdropMetadataHelper metadataHelper;

    @InjectMocks
    private BookdropMetadataService bookdropMetadataService;

    private BookdropFileEntity sampleFile;

    @BeforeEach
    void setup() {
        sampleFile = new BookdropFileEntity();
        sampleFile.setId(1L);
        sampleFile.setFileName("book.epub");
        sampleFile.setFilePath("/tmp/book.epub");
    }

    @Test
    void attachInitialMetadata_shouldExtractAndSaveMetadata() throws Exception {
        BookMetadata metadata = BookMetadata.builder().title("Test Book").build();

        when(bookdropFileRepository.findById(1L)).thenReturn(Optional.of(sampleFile));
        when(metadataExtractorFactory.extractMetadata(eq(BookFileExtension.EPUB), any(File.class))).thenReturn(metadata);
        when(objectMapper.writeValueAsString(any(BookMetadata.class))).thenReturn("{\"title\":\"Test Book\"}");
        when(bookdropFileRepository.save(any(BookdropFileEntity.class))).thenReturn(sampleFile);

        BookdropFileEntity result = bookdropMetadataService.attachInitialMetadata(1L);

        assertThat(result).isNotNull();
        assertThat(result.getOriginalMetadata()).contains("Test Book");
        assertThat(result.getUpdatedAt()).isBeforeOrEqualTo(Instant.now());
        verify(bookdropFileRepository).save(any(BookdropFileEntity.class));
    }

    @Test
    void attachInitialMetadata_shouldThrowWhenFileMissing() {
        when(bookdropFileRepository.findById(99L)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> bookdropMetadataService.attachInitialMetadata(99L));
    }

    @Test
    void attachFetchedMetadata_shouldUpdateEntityWithFetchedData() throws Exception {
        sampleFile.setOriginalMetadata("{\"title\":\"Old Book\"}");
        AppSettings settings = new AppSettings();
        BookMetadata fetched = BookMetadata.builder().title("New Title").build();

        when(bookdropFileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(bookdropFileRepository.findById(1L)).thenReturn(Optional.of(sampleFile));
        when(appSettingService.getAppSettings()).thenReturn(settings);
        when(metadataRefreshService.prepareProviders(any())).thenReturn(List.of());
        when(objectMapper.readValue(sampleFile.getOriginalMetadata(), BookMetadata.class)).thenReturn(fetched);
        when(metadataRefreshService.fetchMetadataForBook(any(), any(Book.class))).thenReturn(Map.of());
        when(metadataRefreshService.buildFetchMetadata(any(), any(), any(), any())).thenReturn(fetched);
        when(objectMapper.writeValueAsString(fetched)).thenReturn("{\"title\":\"New Title\"}");

        BookdropFileEntity result = bookdropMetadataService.attachFetchedMetadata(1L);

        assertThat(result.getFetchedMetadata()).contains("New Title");
        assertThat(result.getStatus()).isEqualTo(PENDING_REVIEW);
        verify(bookdropFileRepository).save(result);
    }

    @Test
    void attachInitialMetadata_shouldHandleNullCoverGracefully() throws Exception {
        BookMetadata metadata = BookMetadata.builder().title("No Cover Book").build();

        when(bookdropFileRepository.findById(1L)).thenReturn(Optional.of(sampleFile));
        when(metadataExtractorFactory.extractMetadata(eq(BookFileExtension.EPUB), any(File.class))).thenReturn(metadata);
        when(objectMapper.writeValueAsString(metadata)).thenReturn("{\"title\":\"No Cover Book\"}");
        when(bookdropFileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BookdropFileEntity result = bookdropMetadataService.attachInitialMetadata(1L);

        assertThat(result.getOriginalMetadata()).contains("No Cover Book");
        verify(bookdropFileRepository).save(result);
    }

    @Test
    void extractInitialMetadata_shouldThrowForUnsupportedFileExtension() {
        sampleFile.setFileName("book.txt");
        sampleFile.setFilePath("/tmp/book.txt");

        when(bookdropFileRepository.findById(sampleFile.getId())).thenReturn(Optional.of(sampleFile));

        assertThatThrownBy(() -> bookdropMetadataService.attachInitialMetadata(sampleFile.getId())).isInstanceOf(APIException.class)
                .hasMessageContaining("Invalid file format");
    }

    @Test
    void attachFetchedMetadata_shouldSleepIfGoodreadsIncluded() throws Exception {
        sampleFile.setOriginalMetadata("{\"title\":\"Book\"}");
        AppSettings settings = new AppSettings();
        BookMetadata fetched = BookMetadata.builder().title("Fetched Book").build();

        when(bookdropFileRepository.findById(1L)).thenReturn(Optional.of(sampleFile));
        when(appSettingService.getAppSettings()).thenReturn(settings);
        when(metadataRefreshService.prepareProviders(any())).thenReturn(List.of(MetadataProvider.GoodReads));
        when(objectMapper.readValue(anyString(), eq(BookMetadata.class))).thenReturn(fetched);
        when(metadataRefreshService.fetchMetadataForBook(any(), any(Book.class))).thenReturn(Map.of());
        when(metadataRefreshService.buildFetchMetadata(any(), any(), any(), any())).thenReturn(fetched);
        when(objectMapper.writeValueAsString(fetched)).thenReturn("{\"title\":\"Fetched Book\"}");
        when(bookdropFileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BookdropFileEntity result = bookdropMetadataService.attachFetchedMetadata(1L);

        assertThat(result.getFetchedMetadata()).contains("Fetched Book");
        assertThat(result.getStatus()).isEqualTo(PENDING_REVIEW);
        verify(bookdropFileRepository).save(result);
    }

    @Test
    void attachInitialMetadata_shouldApplyFilenamePatternWhenFileHasNoEmbeddedTitle() throws Exception {
        sampleFile.setFileName("Bede's History -- Bede -- Oxford -- Anna's Archive.epub");
        sampleFile.setFilePath("/tmp/Bede's History -- Bede -- Oxford -- Anna's Archive.epub");
        String filenameTitle = "Bede's History -- Bede -- Oxford -- Anna's Archive";
        BookMetadata fromFile = BookMetadata.builder().title(filenameTitle).build();
        BookMetadata fromPattern = BookMetadata.builder().title("Bede's History").authors(List.of("Bede")).build();

        when(bookdropFileRepository.findById(1L)).thenReturn(Optional.of(sampleFile));
        when(metadataExtractorFactory.extractMetadata(eq(BookFileExtension.EPUB), any(File.class))).thenReturn(fromFile);
        when(appSettingService.getAppSettings()).thenReturn(appSettingsWithPattern("{Title} -- {Authors} -- *"));
        when(filenamePatternExtractor.extractFromFilename(sampleFile.getFileName(), "{Title} -- {Authors} -- *")).thenReturn(fromPattern);
        when(bookdropFileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        bookdropMetadataService.attachInitialMetadata(1L);

        verify(metadataHelper).mergeMetadata(fromFile, fromPattern);
    }

    @Test
    void attachInitialMetadata_shouldNotApplyFilenamePatternWhenFileHasEmbeddedTitle() throws Exception {
        sampleFile.setFileName("Bede's History -- Bede -- Oxford -- Anna's Archive.epub");
        sampleFile.setFilePath("/tmp/Bede's History -- Bede -- Oxford -- Anna's Archive.epub");
        BookMetadata embedded = BookMetadata.builder().title("Ecclesiastical History of the English People").build();

        when(bookdropFileRepository.findById(1L)).thenReturn(Optional.of(sampleFile));
        when(metadataExtractorFactory.extractMetadata(eq(BookFileExtension.EPUB), any(File.class))).thenReturn(embedded);
        when(bookdropFileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        bookdropMetadataService.attachInitialMetadata(1L);

        verifyNoInteractions(filenamePatternExtractor);
        verifyNoInteractions(metadataHelper);
    }

    @Test
    void attachInitialMetadata_shouldNotApplyFilenamePatternWhenPatternIsBlank() throws Exception {
        BookMetadata fromFile = BookMetadata.builder().title("book").build();

        when(bookdropFileRepository.findById(1L)).thenReturn(Optional.of(sampleFile));
        when(metadataExtractorFactory.extractMetadata(eq(BookFileExtension.EPUB), any(File.class))).thenReturn(fromFile);
        when(appSettingService.getAppSettings()).thenReturn(appSettingsWithPattern(""));
        when(bookdropFileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        bookdropMetadataService.attachInitialMetadata(1L);

        verifyNoInteractions(filenamePatternExtractor);
        verifyNoInteractions(metadataHelper);
    }

    @Test
    void attachInitialMetadata_shouldKeepFilenameTitleWhenPatternDoesNotMatch() throws Exception {
        BookMetadata fromFile = BookMetadata.builder().title("book").build();

        when(bookdropFileRepository.findById(1L)).thenReturn(Optional.of(sampleFile));
        when(metadataExtractorFactory.extractMetadata(eq(BookFileExtension.EPUB), any(File.class))).thenReturn(fromFile);
        when(appSettingService.getAppSettings()).thenReturn(appSettingsWithPattern("{Title} -- {Authors} -- *"));
        when(filenamePatternExtractor.extractFromFilename(anyString(), anyString())).thenReturn(null);
        when(bookdropFileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        bookdropMetadataService.attachInitialMetadata(1L);

        verifyNoInteractions(metadataHelper);
        assertThat(fromFile.getTitle()).isEqualTo("book");
    }

    private AppSettings appSettingsWithPattern(String pattern) {
        AppSettings settings = new AppSettings();
        settings.setBookdropFilenamePattern(pattern);
        return settings;
    }

    @Test
    void attachFetchedMetadata_shouldThrowOnJsonProcessingError() throws Exception {
        sampleFile.setOriginalMetadata("{invalidJson}");

        when(bookdropFileRepository.findById(1L)).thenReturn(Optional.of(sampleFile));
        when(appSettingService.getAppSettings()).thenReturn(new AppSettings());
        when(objectMapper.readValue(anyString(), eq(BookMetadata.class)))
                .thenThrow(new JacksonException("Invalid JSON") {
                });

        assertThatThrownBy(() -> bookdropMetadataService.attachFetchedMetadata(1L))
                .isInstanceOf(JacksonException.class);
    }
}