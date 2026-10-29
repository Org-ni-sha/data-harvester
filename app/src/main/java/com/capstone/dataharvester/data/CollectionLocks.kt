package com.capstone.dataharvester.data

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide locks for writes to the collected-data tables.
 *
 * Both collector ticks hold [db] around their read-checkpoint → insert → write-checkpoint
 * sequence, and Reset holds it while deleting. A tick that was already running when the
 * service was stopped therefore cannot insert rows right after Reset cleared the tables.
 */
object CollectionLocks {
    val db = Mutex()
}
