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
    private final GenerationReadiness readiness;

    public GenerationCapabilitiesVO capabilities() {
        var selected = profile.activeProfile();
        var presets = new EnumMap<GenerationQuality, QualityPreset>(GenerationQuality.class);
        selected.presets().forEach((quality, preset) -> presets.put(quality,
                new QualityPreset(selected.simulated(), preset.inferenceSteps(), preset.guidanceScale(), preset.octreeResolution())));
        var standard = selected.presets().get(GenerationQuality.STANDARD);
        if (standard == null) { standard = selected.presets().values().iterator().next(); }
        Map<String, ParameterCapability> advanced = new LinkedHashMap<>();
        advanced.put("removeBackground", new ParameterCapability(true, true, null, null, null));
        advanced.put("seed", new ParameterCapability(true, null, 0, selected.maximumSeed(), null));
        advanced.put("inferenceSteps", new ParameterCapability(true, standard.inferenceSteps(), selected.minimumSteps(), selected.maximumSteps(), null));
        advanced.put("guidanceScale", new ParameterCapability(true, standard.guidanceScale(), selected.minimumGuidance(), selected.maximumGuidance(), null));
        advanced.put("octreeResolution", new ParameterCapability(true, standard.octreeResolution(), null, null, List.copyOf(selected.octreeResolutions())));
        return new GenerationCapabilitiesVO(profile.getMode(), profile.getMode() == GenerationMode.MOCK
                || (profile.getMode() == GenerationMode.HUNYUAN_LOCAL && readiness.available()),
                profile.getMode() == GenerationMode.MOCK, selected.modelId(), selected.id(),
                profile.getMode() == GenerationMode.HUNYUAN_LOCAL ? null
                        : "仅模拟参数与任务链路，不执行真实推理、去背景或贴图；参数不会改变演示模型。",
                List.of("SINGLE_IMAGE"), presets, false, true, advanced, selected.maximumVoxelSteps(),
                new UploadConstraints(List.of("JPEG", "PNG"), upload.getMaxUploadBytes(), upload.getMaxImagePixels()));
    }
}
