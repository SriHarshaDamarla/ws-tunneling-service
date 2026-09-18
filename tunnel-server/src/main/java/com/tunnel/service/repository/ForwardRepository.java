package com.tunnel.service.repository;

import com.tunnel.service.model.Direction;
import com.tunnel.service.model.Forward;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ForwardRepository {
    private final JdbcTemplate jdbc;

    private final RowMapper<Forward> mapper = (rs, rowNum) -> {
        Forward f = new Forward();
        f.setId(rs.getString("id"));
        f.setDirection(Direction.valueOf(rs.getString("direction")));
        f.setAgentId(rs.getString("agent_id"));
        f.setListenPort(rs.getInt("listen_port"));
        f.setTargetHost(rs.getString("target_host"));
        f.setTargetPort(rs.getInt("target_port"));
        f.setEnabled(rs.getBoolean("enabled"));
        return f;
    };

    public void save(Forward f) {
        int rows = jdbc.update("""
                UPDATE forward SET direction=?, agent_id=?, listen_port=?, target_host=?, target_port=?, enabled=?
                WHERE id=?
                """,
                f.getDirection().name(), f.getAgentId(), f.getListenPort(), f.getTargetHost(), f.getTargetPort(),
                f.isEnabled(), f.getId());
        if (rows == 0) {
            jdbc.update("""
                    INSERT INTO forward (id, direction, agent_id, listen_port, target_host, target_port, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    f.getId(), f.getDirection().name(), f.getAgentId(), f.getListenPort(), f.getTargetHost(),
                    f.getTargetPort(), f.isEnabled());
        }
    }

    public void deleteById(String id) {
        jdbc.update("DELETE FROM forward WHERE id = ?", id);
    }

    public List<Forward> findAll() {
        return jdbc.query("SELECT * FROM forward", mapper);
    }
}

