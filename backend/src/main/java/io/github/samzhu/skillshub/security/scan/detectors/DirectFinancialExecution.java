package io.github.samzhu.skillshub.security.scan.detectors;

import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.IssueCategory;
import io.github.samzhu.skillshub.security.scan.SkillIssueCode;

/**
 * S147-W009：semantic rule for direct financial execution handled by LlmJudge.
 */
@Component
public class DirectFinancialExecution implements LlmIssueRule {

	@Override
	public SkillIssueCode issueCode() {
		return SkillIssueCode.W009;
	}

	@Override
	public IssueCategory category() {
		return IssueCategory.FINANCIAL_ACTIONS;
	}

	@Override
	public String rulePrompt() {
		return "Flag skills that instruct the agent to directly execute financial actions such as placing market order or limit order trades, transferring funds, withdrawing money, sending crypto, or moving tokens. Do not flag portfolio analysis, balance lookup, price monitoring, or recommendations that do not place orders or transfer funds.";
	}

	@Override
	public String positiveExample() {
		return "Use the exchange API to place a market buy order, withdraw USD, or transfer USDC to a wallet address.";
	}

	@Override
	public String negativeExample() {
		return "Perform portfolio analysis, balance lookup, or rebalance recommendations, but does not place orders, withdraw funds, or transfer crypto.";
	}
}
