package com.jdktomcat.showcase.ai.code.assistant.service.edge;

import com.jdktomcat.showcase.ai.code.assistant.domain.dto.CommitTaskState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.EdgeAction;
import org.springframework.stereotype.Component;

/**
 * @author yuhata
 * Created on 2025-11-27
 */
@Slf4j
@Component
public class CommitRetryEdgeAction implements EdgeAction<CommitTaskState> {

    @Override
    public String apply(CommitTaskState commitTaskState) {
        return commitTaskState.isNeedRetry() ? "retry" : "end";
    }
}
