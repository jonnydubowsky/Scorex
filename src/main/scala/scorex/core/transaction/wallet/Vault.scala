package scorex.core.transaction.wallet

import scorex.core.{NodeViewComponent, NodeViewModifier, PersistentNodeViewModifier, VersionTag}
import scorex.core.transaction.Transaction
import scorex.core.transaction.box.proposition.Proposition

import scala.util.Try

/**
  * Abstract interface for Vault, a storage for node-specific information
  */

trait Vault[P <: Proposition, TX <: Transaction[P],
            PMOD <: PersistentNodeViewModifier, V <: Vault[P, TX, PMOD, V]] extends NodeViewComponent {
  self: V =>

  def scanOffchain(tx: TX): V

  def scanOffchain(txs: Seq[TX]): V

  def scanPersistent(modifier: PMOD): V

  def scanPersistent(modifiers: Option[PMOD]): V = modifiers.foldLeft(this) { case (v, mod) =>
    v.scanPersistent(mod)
  }

  def rollback(to: VersionTag): Try[V]
}