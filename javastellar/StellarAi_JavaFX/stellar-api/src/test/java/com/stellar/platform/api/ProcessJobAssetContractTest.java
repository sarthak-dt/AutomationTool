package com.stellar.platform.api;

import com.stellar.platform.api.assets.FileAssetUploader;
import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.PlanPreviewRequest;
import com.stellar.platform.api.dto.ProcessJobRequest;
import com.stellar.platform.api.dto.ProjectDto;
import com.stellar.platform.api.identity.FileAssetKinds;
import com.stellar.platform.api.identity.PlatformIds;
import com.stellar.platform.api.local.LocalStellarPlatformClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessJobAssetContractTest {

    @TempDir
    Path temp;

    @Test
    void processAndPreviewUseAssetIdsNotDesktopPaths() throws Exception {
        LocalStellarPlatformClient client = LocalStellarPlatformClient.createDefault(temp);
        try {
            String projectId = PlatformIds.newId();
            ProjectDto project = client.projects().createProject(
                    new CreateProjectRequest("Asset Contract", "", projectId));
            assertEquals(projectId, project.projectId());

            Path measurement = temp.resolve("inputs").resolve("m.mf4");
            Path definition = temp.resolve("inputs").resolve("d.dbc");
            Files.createDirectories(measurement.getParent());
            Files.writeString(measurement, "not-a-real-mf4");
            Files.writeString(definition, "VERSION \"\"\nNS_ :\nBS_:\n");

            FileAssetDto mAsset = FileAssetUploader.ensureAsset(
                    client.assets(), projectId, "src-1", FileAssetKinds.MEASUREMENT, measurement, true);
            FileAssetDto dAsset = FileAssetUploader.ensureAsset(
                    client.assets(), projectId, "def-1", FileAssetKinds.DEFINITION, definition, true);

            var preview = client.processing().previewPlan(projectId,
                    new PlanPreviewRequest(mAsset.fileAssetId(), dAsset.fileAssetId(), "All"));
            assertTrue(preview.valid());
            assertFalse(preview.measurementAssetId().contains("\\") || preview.measurementAssetId().contains("/"));
            assertEquals(mAsset.fileAssetId(), preview.measurementAssetId());

            ProcessJobRequest jobRequest = new ProcessJobRequest(
                    "src-1", mAsset.fileAssetId(), dAsset.fileAssetId());
            assertEquals(mAsset.fileAssetId(), jobRequest.measurementAssetId());
            assertEquals(dAsset.fileAssetId(), jobRequest.definitionAssetId());
        } finally {
            client.close();
        }
    }
}
