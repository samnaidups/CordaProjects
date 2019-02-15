package com.template

import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey

// *********
// * States *
// *********
data class LoanProposalState(

        val amount: Int,
        val roi:Int,
        val installments:Int,

        val lender: AbstractParty,
        val borrower: AbstractParty,
        val proposer: AbstractParty,
        val proposee: AbstractParty,
        override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState {
    override val participants = listOf(proposer, proposee)
}

data class LoanAgreedState(

        val amount: Int,
        val roi:Int,
        val installments:Int,
        val paidamount: Int ,
        val lender: AbstractParty,
        val borrower: AbstractParty,

        override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState {
    override val participants = listOf(lender, borrower)
}


// ***********************************************************************
// * Contract for Loan Proposal and Agreement between Lender and Borrower*
// ***********************************************************************

open class LoanProposalAndAgreementContract : Contract {
    companion object {
        val PROPOSAL_PROCESS_ID = "com.template.LoanProposalAndAgreementContract"
    }

    // A transaction is considered valid if the verify() function of the contract of each of the transaction's input
    // and output states does not throw an exception.
    override fun verify(tx: LedgerTransaction) {
        val cmd = tx.commands.requireSingleCommand<Commands>()
        when (cmd.value) {
            is Commands.ProposeLoan -> requireThat {
                "There are no inputs" using (tx.inputStates.isEmpty())
                "There is exactly one output" using (tx.outputStates.size == 1)
                "The single output is of type LoanProposalState" using (tx.outputsOfType<LoanProposalState>().size == 1)
                "There is exactly one command" using (tx.commands.size == 1)
                "There is no timestamp" using (tx.timeWindow == null)

                val output = tx.outputsOfType<LoanProposalState>().single()
                "The lender and borrower are the proposer and the proposee" using (setOf(output.lender, output.borrower) == setOf(output.proposer, output.proposee))

                "The proposer is a required signer" using (cmd.signers.contains(output.proposer.owningKey))
                "The proposee is a required signer" using (cmd.signers.contains(output.proposee.owningKey))
            }

            is Commands.ModifyROI -> requireThat {
                "There is exactly one input" using (tx.inputStates.size == 1)
                "The single input is of type LoanProposalState" using (tx.inputsOfType<LoanProposalState>().size == 1)
                "There is exactly one output" using (tx.outputStates.size == 1)
                "The single output is of type LoanProposalState" using (tx.outputsOfType<LoanProposalState>().size == 1)
                "There is exactly one command" using (tx.commands.size == 1)
                "There is no timestamp" using (tx.timeWindow == null)

                val output = tx.outputsOfType<LoanProposalState>().single()
                val input = tx.inputsOfType<LoanProposalState>().single()

                "The roi is modified in the output" using (output.roi != input.roi)
                "The borrower is unmodified in the output" using (input.borrower == output.borrower)
                "The lender is unmodified in the output" using (input.lender == output.lender)

                "The proposer is a required signer" using (cmd.signers.contains(output.proposer.owningKey))
                "The proposee is a required signer" using (cmd.signers.contains(output.proposee.owningKey))
            }

            is Commands.ModifyInstallments -> requireThat {
                "There is exactly one input" using (tx.inputStates.size == 1)
                "The single input is of type LoanProposalState" using (tx.inputsOfType<LoanProposalState>().size == 1)
                "There is exactly one output" using (tx.outputStates.size == 1)
                "The single output is of type LoanProposalState" using (tx.outputsOfType<LoanProposalState>().size == 1)
                "There is exactly one command" using (tx.commands.size == 1)
                "There is no timestamp" using (tx.timeWindow == null)

                val output = tx.outputsOfType<LoanProposalState>().single()
                val input = tx.inputsOfType<LoanProposalState>().single()

                "The roi is modified in the output" using (output.installments != input.installments)
                "The borrower is unmodified in the output" using (input.borrower == output.borrower)
                "The lender is unmodified in the output" using (input.lender == output.lender)

                "The proposer is a required signer" using (cmd.signers.contains(output.proposer.owningKey))
                "The proposee is a required signer" using (cmd.signers.contains(output.proposee.owningKey))
            }

            is Commands.AcceptLoan -> requireThat {
                "There is exactly one input" using (tx.inputStates.size == 1)
                "The single input is of type LoanProposalState" using (tx.inputsOfType<LoanProposalState>().size == 1)
                "There is exactly one output" using (tx.outputStates.size == 1)
                "The single output is of type LoanTradeState" using (tx.outputsOfType<LoanAgreedState>().size == 1)
                "There is exactly one command" using (tx.commands.size == 1)
                "There is no timestamp" using (tx.timeWindow == null)

                val input = tx.inputsOfType<LoanProposalState>().single()
                val output = tx.outputsOfType<LoanAgreedState>().single()

                "The amount is unmodified in the output" using (output.amount == input.amount)
                "The borrower is unmodified in the output" using (input.borrower == output.borrower)
                "The lender is unmodified in the output" using (input.lender == output.lender)

                "The proposer is a required signer" using (cmd.signers.contains(input.proposer.owningKey))
                "The proposee is a required signer" using (cmd.signers.contains(input.proposee.owningKey))
            }
        }
    }

    // Used to indicate the transaction's intent.
    sealed class Commands : TypeOnlyCommandData() {
        class ProposeLoan : Commands()
        class ModifyROI: Commands()
        class ModifyInstallments: Commands()
        class AcceptLoan : Commands()
    }
}

// ***********************************************************************
// * Contract for Loan Request and Settlement between Lender and Borrower*
// ***********************************************************************

open class LoanRequestandSettlementContract : Contract {
    companion object {
        val LOAN_REQUEST_SETTLEMENT_ID = "com.template.LoanRequestandSettlementContract"
    }

    // Used to indicate the transaction's intent.
    sealed class Commands : TypeOnlyCommandData() {
        class RequestLoan : Commands()
        class SettleLoan : Commands()
        class PayInterest : Commands()
        class PayPrincipal : Commands()
    }

    private fun keysFromParticipants(loanagreement: LoanAgreedState): Set<PublicKey> {
        return loanagreement.participants.map {
            it.owningKey
        }.toSet()
    }


    override fun verify(tx: LedgerTransaction): Unit {
        val command = tx.commands.requireSingleCommand<Commands>()
        val setOfSigners = command.signers.toSet()
        when (command.value) {
            is Commands.RequestLoan -> verifyRequest(tx, setOfSigners)

            is Commands.SettleLoan -> verifySettle(tx, setOfSigners)
            else -> throw IllegalArgumentException("Unrecognised command.")
        }
    }

    private fun verifyRequest(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
                "There are no inputs" using (tx.inputStates.isEmpty())
                "There is exactly one output" using (tx.outputStates.size == 1)
                "The single output is of type LoanAgreedState" using (tx.outputsOfType<LoanAgreedState>().size == 1)
                "There is exactly one command" using (tx.commands.size == 1)
               // "There is no timestamp" using (tx.timeWindow == null)

                val LoanIssuedOutput = tx.outputsOfType<LoanAgreedState>().single()
                "A newly issued Loan must have a positive amount." using (LoanIssuedOutput.amount > 0)
                "The lender and borrower cannot be the same identity." using (LoanIssuedOutput.borrower != LoanIssuedOutput.lender)
                "Both lender and borrower together only may sign obligation issue transaction." using (signers == keysFromParticipants(LoanIssuedOutput))
            }

    private fun verifySettle(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // Check for the presence of an input obligation state.
        val LoanSettlementInput = tx.inputsOfType<LoanAgreedState>().single()
        "There must be one input state for Agreed Loan." using (tx.inputStates.size == 1)

        val AccountBalanceReturnedbyOracle:Int = 1 //assuming that there is an oracle service that returns the account balance of a given account

        val amountOutstanding = LoanSettlementInput.amount - LoanSettlementInput.paidamount
        "The amount settled cannot be more than the amount outstanding." using (amountOutstanding >= AccountBalanceReturnedbyOracle)

        val AgreedLoanOutputs = tx.outputsOfType<LoanAgreedState>()

        // Check to see if we need an output obligation or not.
        if (amountOutstanding == AccountBalanceReturnedbyOracle) {
            // If the obligation has been fully settled then there should be no obligation output state.
            "There must be no output obligation as it has been fully settled." using (AgreedLoanOutputs.isEmpty())
        } else {
            // If the obligation has been partially settled then it should still exist.
            "There must be one output obligation." using (AgreedLoanOutputs.size == 1)

            // Check only the paid property changes.
            val outputLoanstate = AgreedLoanOutputs.single()
            "The amount may not change when settling." using (LoanSettlementInput.amount == outputLoanstate.amount)
            "The borrower may not change when settling." using (LoanSettlementInput.borrower == outputLoanstate.borrower)
            "The lender may not change when settling." using (LoanSettlementInput.lender == outputLoanstate.lender)
            "The linearId may not change when settling." using (LoanSettlementInput.linearId == outputLoanstate.linearId)
        }

        // Checks the required parties have signed.
        "Both lender and borrower together only must sign obligation settle transaction." using
                (signers == keysFromParticipants(LoanSettlementInput))


    }


}

