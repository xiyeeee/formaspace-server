package com.xiye.formaspace.asset.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xiye.formaspace.asset.entity.ModelAssetEntity;
import com.xiye.formaspace.asset.mapper.ModelAssetMapper;
import com.xiye.formaspace.asset.vo.ModelAssetVO;
import com.xiye.formaspace.common.enums.FilePurpose;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.file.entity.FileEntity;
import com.xiye.formaspace.file.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ModelAssetService {
    private final ModelAssetMapper assets;
    private final FileService files;

    public ModelAssetEntity requireOwned(long id, long userId) {
        var asset = assets.selectOne(Wrappers.<ModelAssetEntity>lambdaQuery()
                .eq(ModelAssetEntity::getId, id).eq(ModelAssetEntity::getUserId, userId));
        if (asset == null) { throw new BusinessException(ErrorCode.NOT_FOUND); }
        return asset;
    }

    public ModelAssetVO detail(long id, long userId) {
        var asset = requireOwned(id, userId);
        return new ModelAssetVO(asset.getId().toString(), asset.getGenerationTaskId().toString(), asset.getName(),
                asset.getFormat(), asset.getSizeBytes(), Boolean.TRUE.equals(asset.getSimulated()),
                "/api/assets/" + asset.getId() + "/model", null, asset.getCreatedAt());
    }

    public FileEntity modelFile(long id, long userId) {
        var asset = requireOwned(id, userId);
        return files.requireOwned(asset.getModelFileId(), userId, FilePurpose.MODEL);
    }
}
