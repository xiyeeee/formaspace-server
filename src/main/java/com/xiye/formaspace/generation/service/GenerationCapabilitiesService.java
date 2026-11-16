package com.xiye.formaspace.generation.service;

import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.enums.*;
import com.xiye.formaspace.generation.vo.GenerationCapabilitiesVO;
import com.xiye.formaspace.generation.vo.GenerationCapabilitiesVO.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GenerationCapabilitiesService {
    private final GenerationProperties profile;
    private final FileStorageProperties upload;

    public GenerationCapabilitiesVO capabilities() {
        var presets = new EnumMap<GenerationQuality, QualityPreset>(GenerationQuality.class);
        profile.getQualityPresets().forEach((quality, preset) -> presets.put(quality,
                new QualityPreset(true, preset.inferenceSteps(), preset.guidanceScale(), preset.octreeResolution())));
        var standard = profile.getQualityPresets().get(GenerationQuality.STANDARD);
        if (standard == null) { standard = profile.getQualityPresets().values().iterator().next(); }
        Map<String, ParameterCapability> advanced = new LinkedHashMap<>();
        advanced.put("removeBackground", new ParameterCapability(true, true, null, null, null));
        advanced.put("seed", new ParameterCapability(true, null, 0, Integer.MAX_VALUE, null));
        advanced.put("inferenceSteps", new ParameterCapability(true, standard.inferenceSteps(), profile.getMinimumSteps(), profile.getMaximumSteps(), null));
        advanced.put("guidanceScale", new ParameterCapability(true, standard.guidanceScale(), profile.getMinimumGuidance(), profile.getMaximumGuidance(), null));
        advanced.put("octreeResolution", new ParameterCapability(true, standard.octreeResolution(), null, null, List.copyOf(profile.getOctreeResolutions())));
        return new GenerationCapabilitiesVO(profile.getMode(), profile.getMode() == GenerationMode.MOCK,
                profile.getMode() == GenerationMode.MOCK, null, profile.getProfileId(),
                "仅模拟参数与任务链路，不执行真实推理、去背景或贴图；参数不会改变演示模型。",
                List.of("SINGLE_IMAGE"), presets, false, true, advanced, profile.getMaximumVoxelSteps(),
                new UploadConstraints(List.of("JPEG", "PNG"), upload.getMaxUploadBytes(), upload.getMaxImagePixels()));
    }
}
