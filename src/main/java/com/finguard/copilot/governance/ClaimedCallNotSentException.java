package com.finguard.copilot.governance;

import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;

/** Positive ownership proof for this attempt, after releasing proven-not-sent money. */
public final class ClaimedCallNotSentException extends CopilotException {
    public ClaimedCallNotSentException(ErrorCode code) {super(code,code.title()+": request not sent after claim");}
}
