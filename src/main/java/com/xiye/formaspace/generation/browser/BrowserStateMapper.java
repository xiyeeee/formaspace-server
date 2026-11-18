package com.xiye.formaspace.generation.browser;

import org.apache.ibatis.annotations.*;

@Mapper
public interface BrowserStateMapper {
    @Select("SELECT task_id,sequence,snapshot_json FROM fs_generation_browser_state WHERE task_id=#{id}") State find(long id);
    @Insert("INSERT INTO fs_generation_browser_state(task_id,sequence,snapshot_json) VALUES(#{taskId},#{sequence},#{snapshotJson}) ON DUPLICATE KEY UPDATE sequence=VALUES(sequence),snapshot_json=VALUES(snapshot_json)")
    int save(State state);
    @Insert("INSERT IGNORE INTO fs_generation_browser_dedup(task_id,source_key) VALUES(#{id},#{key})") int accept(@Param("id")long id,@Param("key")String key);
    record State(long taskId,long sequence,String snapshotJson){}
}
