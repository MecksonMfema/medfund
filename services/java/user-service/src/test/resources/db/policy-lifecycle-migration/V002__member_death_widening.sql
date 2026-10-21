-- Mirror of production V140 (actuarial Phase 14 §D). The Member R2DBC entity
-- now maps death_date + cause_of_death, so every memberRepository.save() lists
-- both in the UPDATE column set — this baseline needs them or every
-- policy-lifecycle IT SQL-grammars out.

ALTER TABLE members
    ADD COLUMN IF NOT EXISTS death_date       DATE,
    ADD COLUMN IF NOT EXISTS cause_of_death   VARCHAR(80);
