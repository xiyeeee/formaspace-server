package com.xiye.formaspace.file;

import com.xiye.formaspace.common.enums.FilePurpose;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.file.entity.FileEntity;
import com.xiye.formaspace.file.mapper.FileMapper;
import com.xiye.formaspace.file.service.*;
import com.xiye.formaspace.file.storage.LocalFileStorage;
import com.xiye.formaspace.testsupport.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileLifecycleTests {
    @TempDir Path temporary;

    @Test
    void databaseInsertFailureRemovesNewPhysicalFile() throws Exception {
        var properties = new FileStorageProperties();
        properties.setRoot(temporary.toRealPath());
        var files = mock(FileMapper.class);
        doThrow(new DataAccessResourceFailureException("test SQL")).when(files).insert(any(FileEntity.class));
        var transactions = new TestTransactions();
        var service = new FileService(files, new LocalFileStorage(properties), properties, new SourceImageValidator(properties), transactions);
        assertThatThrownBy(() -> service.upload(1, new MockMultipartFile("file", "a.png", "image/png", MediaFixtures.png())))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(transactions.rollbacks).isEqualTo(1);
        try (var children = Files.list(temporary)) { assertThat(children.count()).isZero(); }
    }

    @Test
    void uncertainCommitDoesNotDeleteAReferencedPhysicalFile() throws Exception {
        var properties = new FileStorageProperties();
        properties.setRoot(temporary.toRealPath());
        var files = mock(FileMapper.class);
        doAnswer(invocation -> { ((FileEntity) invocation.getArgument(0)).setId(1L); return 1; }).when(files).insert(any(FileEntity.class));
        when(files.selectCount(any())).thenReturn(1L);
        var transactions = new TestTransactions() {
            @Override protected void doCommit(DefaultTransactionStatus status) { throw new TransactionSystemException("uncertain commit"); }
        };
        var service = new FileService(files, new LocalFileStorage(properties), properties, new SourceImageValidator(properties), transactions);
        assertThatThrownBy(() -> service.upload(1, new MockMultipartFile("file", "a.png", "image/png", MediaFixtures.png())))
                .isInstanceOf(TransactionSystemException.class);
        try (var children = Files.list(temporary)) { assertThat(children.count()).isEqualTo(1); }
    }

    @Test
    void nonOwnedAndWrongPurposeAreUniformlyNotFound() throws Exception {
        var properties = new FileStorageProperties();
        properties.setRoot(temporary.toRealPath());
        var service = new FileService(mock(FileMapper.class), new LocalFileStorage(properties), properties,
                new SourceImageValidator(properties), new TestTransactions());
        assertThatThrownBy(() -> service.requireOwned(1, 2, FilePurpose.SOURCE_IMAGE))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
