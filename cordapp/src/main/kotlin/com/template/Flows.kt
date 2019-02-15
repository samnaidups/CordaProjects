package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object LoanProposalFlow {
    enum class Role { Lender , Borrower }

    @InitiatingFlow
    @StartableByRPC
    class Initiator(val role: Role, val amount: Int, val roi:Int, val installments: Int, val counterparty: Party) : FlowLogic<UniqueIdentifier>() {
        override val progressTracker = ProgressTracker()

        @Suspendable
        override fun call(): UniqueIdentifier {
            // Creating the output.
            val (lender, borrower) = when (role) {
                Role.Lender -> ourIdentity to counterparty
                Role.Borrower -> counterparty to ourIdentity
            }
            val output = LoanProposalState(amount, roi, installments, lender, borrower, ourIdentity, counterparty)

            // Creating the command.
            val commandType = LoanProposalAndAgreementContract.Commands.ProposeLoan()
            val requiredSigners = listOf(ourIdentity.owningKey, counterparty.owningKey)
            val command = Command(commandType, requiredSigners)

            // Building the transaction.
            val notary = serviceHub.networkMapCache.notaryIdentities.first()
            val txBuilder = TransactionBuilder(notary)
            txBuilder.addOutputState(output, LoanProposalAndAgreementContract.PROPOSAL_PROCESS_ID)
            txBuilder.addCommand(command)

            // Signing the transaction ourselves.
            val partStx = serviceHub.signInitialTransaction(txBuilder)

            // Gathering the counterparty's signature.
            val counterpartySession = initiateFlow(counterparty)
            val fullyStx = subFlow(CollectSignaturesFlow(partStx, listOf(counterpartySession)))

            // Finalising the transaction.
            val finalisedTx = subFlow(FinalityFlow(fullyStx))
            return finalisedTx.tx.outputsOfType<LoanProposalState>().single().linearId
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            subFlow(object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) {
                    // No checking to be done.
                }
            })
        }
    }
}

object AcceptProposalFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val proposalId: UniqueIdentifier) : FlowLogic<Unit>() {
        override val progressTracker = ProgressTracker()

        @Suspendable
        override fun call() {
            // Retrieving the input from the vault.
            val inputCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(proposalId))
            val inputStateAndRef = serviceHub.vaultService.queryBy<LoanProposalState>(inputCriteria).states.single()
            val input = inputStateAndRef.state.data

            // Creating the output.
            val output = LoanAgreedState(input.amount, input.roi, input.installments, 0, input.lender, input.borrower, input.linearId)

            // Creating the command.
            val requiredSigners = listOf(input.proposer.owningKey, input.proposee.owningKey)
            val command = Command(LoanProposalAndAgreementContract.Commands.AcceptLoan(), requiredSigners)

            // Building the transaction.
            val notary = inputStateAndRef.state.notary
            val txBuilder = TransactionBuilder(notary)
            txBuilder.addInputState(inputStateAndRef)
            txBuilder.addOutputState(output, LoanProposalAndAgreementContract.PROPOSAL_PROCESS_ID)
            txBuilder.addCommand(command)

            // Signing the transaction ourselves.
            val partStx = serviceHub.signInitialTransaction(txBuilder)

            // Gathering the counterparty's signature.
            val (wellKnownProposer, wellKnownProposee) = listOf(input.proposer, input.proposee).map { serviceHub.identityService.requireWellKnownPartyFromAnonymous(it) }
            val counterparty = if (ourIdentity == wellKnownProposer) wellKnownProposee else wellKnownProposer
            val counterpartySession = initiateFlow(counterparty)
            val fullyStx = subFlow(CollectSignaturesFlow(partStx, listOf(counterpartySession)))

            // Finalising the transaction.
            subFlow(FinalityFlow(fullyStx))
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            subFlow(object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) {
                    val ledgerTx = stx.toLedgerTransaction(serviceHub, false)
                    val proposee = ledgerTx.inputsOfType<LoanProposalState>().single().proposee
                    if (proposee != counterpartySession.counterparty) {
                        throw FlowException("Only the proposee can accept a proposal.")
                    }
                }
            })
        }
    }
}

object ModifyROIFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val proposalId: UniqueIdentifier, val newROI: Int) : FlowLogic<Unit>() {
        override val progressTracker = ProgressTracker()

        @Suspendable
        override fun call() {
            // Retrieving the input from the vault.
            val inputCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(proposalId))
            val inputStateAndRef = serviceHub.vaultService.queryBy<LoanProposalState>(inputCriteria).states.single()
            val input = inputStateAndRef.state.data

            // Creating the output.
            val (wellKnownProposer, wellKnownProposee) = listOf(input.proposer, input.proposee).map { serviceHub.identityService.requireWellKnownPartyFromAnonymous(it) }
            val counterparty = if (ourIdentity == wellKnownProposer) wellKnownProposee else wellKnownProposer
            val output = input.copy(roi = newROI, proposer = ourIdentity, proposee = counterparty)

            // Creating the command.
            val requiredSigners = listOf(input.proposer.owningKey, input.proposee.owningKey)
            val command = Command(LoanProposalAndAgreementContract.Commands.ModifyROI(), requiredSigners)

            // Building the transaction.
            val notary = inputStateAndRef.state.notary
            val txBuilder = TransactionBuilder(notary)
            txBuilder.addInputState(inputStateAndRef)
            txBuilder.addOutputState(output, LoanProposalAndAgreementContract.PROPOSAL_PROCESS_ID)
            txBuilder.addCommand(command)

            // Signing the transaction ourselves.
            val partStx = serviceHub.signInitialTransaction(txBuilder)

            // Gathering the counterparty's signature.
            val counterpartySession = initiateFlow(counterparty)
            val fullyStx = subFlow(CollectSignaturesFlow(partStx, listOf(counterpartySession)))

            // Finalising the transaction.
            subFlow(FinalityFlow(fullyStx))
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            subFlow(object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) {
                    val ledgerTx = stx.toLedgerTransaction(serviceHub, false)
                    val proposee = ledgerTx.inputsOfType<LoanProposalState>().single().proposee
                    if (proposee != counterpartySession.counterparty) {
                        throw FlowException("Only the proposee can modify a proposal.")
                    }
                }
            })
        }
    }
}


object ModifyInstallmentsFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val proposalId: UniqueIdentifier, val newInstallments: Int) : FlowLogic<Unit>() {
        override val progressTracker = ProgressTracker()

        @Suspendable
        override fun call() {
            // Retrieving the input from the vault.
            val inputCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(proposalId))
            val inputStateAndRef = serviceHub.vaultService.queryBy<LoanProposalState>(inputCriteria).states.single()
            val input = inputStateAndRef.state.data

            // Creating the output.
            val (wellKnownProposer, wellKnownProposee) = listOf(input.proposer, input.proposee).map { serviceHub.identityService.requireWellKnownPartyFromAnonymous(it) }
            val counterparty = if (ourIdentity == wellKnownProposer) wellKnownProposee else wellKnownProposer
            val output = input.copy(installments = newInstallments, proposer = ourIdentity, proposee = counterparty)

            // Creating the command.
            val requiredSigners = listOf(input.proposer.owningKey, input.proposee.owningKey)
            val command = Command(LoanProposalAndAgreementContract.Commands.ModifyInstallments(), requiredSigners)

            // Building the transaction.
            val notary = inputStateAndRef.state.notary
            val txBuilder = TransactionBuilder(notary)
            txBuilder.addInputState(inputStateAndRef)
            txBuilder.addOutputState(output, LoanProposalAndAgreementContract.PROPOSAL_PROCESS_ID)
            txBuilder.addCommand(command)

            // Signing the transaction ourselves.
            val partStx = serviceHub.signInitialTransaction(txBuilder)

            // Gathering the counterparty's signature.
            val counterpartySession = initiateFlow(counterparty)
            val fullyStx = subFlow(CollectSignaturesFlow(partStx, listOf(counterpartySession)))

            // Finalising the transaction.
            subFlow(FinalityFlow(fullyStx))
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            subFlow(object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) {
                    val ledgerTx = stx.toLedgerTransaction(serviceHub, false)
                    val proposee = ledgerTx.inputsOfType<LoanProposalState>().single().proposee
                    if (proposee != counterpartySession.counterparty) {
                        throw FlowException("Only the proposee can modify a proposal.")
                    }
                }
            })
        }
    }
}