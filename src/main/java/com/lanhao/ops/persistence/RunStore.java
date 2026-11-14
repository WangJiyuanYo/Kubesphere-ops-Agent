package com.lanhao.ops.persistence;

import com.lanhao.ops.domain.Model.*;
import java.time.*;
import java.util.*;

public interface RunStore {
    record Claim(UUID id, String owner) {}
    Run create(StartRequest request);
    Optional<Run> find(UUID id);
    default Optional<Run> findByRequestKey(String requestKey) { return Optional.empty(); }
    List<Claim> claim(int limit);
    void ensureLease(Claim claim);
    void save(Claim claim, Run run, Instant dueAt);
    Run approve(UUID id, String planId, String actor);
    Run cancel(UUID id, String actor);
    default List<Run> list(int limit, int offset) { return List.of(); }
}
