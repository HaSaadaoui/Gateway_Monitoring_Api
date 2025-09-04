package com.amaris.gatewaymonitoring.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DatabaseMonitoringDao {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public DatabaseMonitoringDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * DAO pour récupérer les informations de localisation depuis la base SQLite.
     */
    public String getLocationGateway(String gatewayId) {
        return jdbcTemplate.queryForObject(
                "SELECT building_name || ', ' || floor_number || ', ' || location_description " +
                        "FROM Gateways WHERE gateway_id = ?",
                String.class,
                gatewayId
        );
    }

}
