package no.beint.vev.benchmark;

import no.beint.vev.VevModel;

@VevModel(entities = {Account.class, UpdateAccount.class})
public final class BenchmarkModel {
    private BenchmarkModel() {
    }
}
