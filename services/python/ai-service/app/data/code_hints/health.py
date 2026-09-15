"""HEALTH code suggester — AHFOZ tariff codes keyed by ICD-10.

Seed covers common conditions across surgery, general medicine, obstetrics,
paediatrics, orthopaedics, imaging, pathology, and mental health. Keyed by
exact ICD-10 or 3-char prefix so `K35.0` matches `K35`. Ordered by
confidence descending in the suggester output.
"""
from __future__ import annotations

from typing import Any

from app.data.code_hints import CodeSuggestion, register
from app.schemas.insurance_line import InsuranceLine


_HINTS: dict[str, list[CodeSuggestion]] = {
    # Digestive
    "K35": [
        CodeSuggestion("23410", "Appendectomy laparoscopic (AHFOZ)",
                       "Common tariff for acute appendicitis", 0.75),
        CodeSuggestion("23400", "Appendectomy open (AHFOZ)",
                       "Alternative when laparoscopy is contraindicated", 0.60),
    ],
    "K80": [CodeSuggestion("28305", "Cholecystectomy laparoscopic",
                            "Standard for gallstones / cholecystitis", 0.75)],
    "K25": [CodeSuggestion("00250", "Peptic ulcer inpatient day rate", "", 0.55),
            CodeSuggestion("29910", "Upper GI endoscopy", "", 0.65)],
    "K52": [CodeSuggestion("00250", "GI inflammatory disorder inpatient day rate", "", 0.55)],
    "K57": [CodeSuggestion("00250", "Diverticular disease inpatient day rate", "", 0.55),
            CodeSuggestion("28900", "Bowel resection", "", 0.50)],
    # Respiratory
    "J18": [CodeSuggestion("00250", "Pneumonia inpatient day rate", "", 0.65),
            CodeSuggestion("07110", "Chest X-ray", "", 0.55)],
    "J20": [CodeSuggestion("00110", "GP consultation acute bronchitis", "", 0.65)],
    "J45": [CodeSuggestion("00110", "GP consultation — asthma exacerbation", "", 0.65),
            CodeSuggestion("00200", "Nebulizer treatment", "", 0.55)],
    "J44": [CodeSuggestion("00250", "COPD exacerbation inpatient day rate", "", 0.65)],
    "J96": [CodeSuggestion("00280", "Respiratory failure ICU day rate", "", 0.70)],
    # Circulatory
    "I10": [CodeSuggestion("00110", "GP consultation — hypertension", "", 0.65),
            CodeSuggestion("30000", "ECG", "", 0.55)],
    "I21": [CodeSuggestion("00280", "Acute MI inpatient day rate", "", 0.75),
            CodeSuggestion("30330", "Coronary angiography", "", 0.65)],
    "I25": [CodeSuggestion("30330", "Coronary angiography", "", 0.60),
            CodeSuggestion("30310", "Cardiac echocardiogram", "", 0.55)],
    "I50": [CodeSuggestion("00250", "Heart failure inpatient day rate", "", 0.65)],
    "I63": [CodeSuggestion("00280", "Stroke inpatient day rate", "", 0.75),
            CodeSuggestion("07500", "CT brain", "", 0.70)],
    # Infectious
    "A09": [CodeSuggestion("00110", "GP consultation — gastroenteritis", "", 0.65)],
    "A15": [CodeSuggestion("00250", "TB inpatient day rate", "", 0.65),
            CodeSuggestion("50100", "TB microbiology", "", 0.60)],
    "B20": [CodeSuggestion("00110", "HIV consultation", "", 0.70),
            CodeSuggestion("50200", "CD4 count", "", 0.65)],
    "B50": [CodeSuggestion("50300", "Malaria smear", "", 0.75),
            CodeSuggestion("00250", "Severe malaria inpatient day rate", "", 0.65)],
    "B54": [CodeSuggestion("50300", "Malaria rapid test", "", 0.75)],
    # Obstetrics
    "O80": [CodeSuggestion("35100", "Vaginal delivery", "Uncomplicated single delivery", 0.80),
            CodeSuggestion("35200", "Postnatal ward day rate", "", 0.60)],
    "O82": [CodeSuggestion("35300", "Caesarean section", "", 0.80),
            CodeSuggestion("35200", "Postnatal ward day rate", "", 0.60)],
    "O60": [CodeSuggestion("35150", "Premature delivery — vaginal", "", 0.60)],
    "Z34": [CodeSuggestion("35000", "Antenatal consultation", "", 0.70),
            CodeSuggestion("07200", "Obstetric ultrasound", "", 0.60)],
    # Paediatrics
    "P07": [CodeSuggestion("00300", "Neonatal ICU day rate", "", 0.75)],
    "P59": [CodeSuggestion("07300", "Phototherapy per session", "", 0.65)],
    # Musculoskeletal
    "M17": [CodeSuggestion("27447", "Total knee replacement", "", 0.80)],
    "M16": [CodeSuggestion("27130", "Total hip replacement", "", 0.80)],
    "M54": [CodeSuggestion("00110", "GP consultation — back pain", "", 0.60),
            CodeSuggestion("07400", "Lumbar MRI", "", 0.55)],
    "S06": [CodeSuggestion("00280", "Head injury inpatient day rate", "", 0.65),
            CodeSuggestion("07500", "CT brain", "", 0.70)],
    "S82": [CodeSuggestion("27500", "Tibia/fibula fracture ORIF", "", 0.70),
            CodeSuggestion("07100", "Lower limb X-ray", "", 0.55)],
    "S72": [CodeSuggestion("27510", "Femur fracture ORIF", "", 0.75)],
    # Genitourinary
    "N18": [CodeSuggestion("90999", "Dialysis session", "", 0.75)],
    "N39": [CodeSuggestion("00110", "GP consultation — UTI", "", 0.65),
            CodeSuggestion("50400", "Urine culture", "", 0.55)],
    "N40": [CodeSuggestion("55200", "TURP prostatectomy", "", 0.65)],
    # Endocrine
    "E10": [CodeSuggestion("00110", "GP consultation — type 1 diabetes", "", 0.65),
            CodeSuggestion("50500", "HbA1c", "", 0.60)],
    "E11": [CodeSuggestion("00110", "GP consultation — type 2 diabetes", "", 0.65),
            CodeSuggestion("50500", "HbA1c", "", 0.60)],
    "E03": [CodeSuggestion("50600", "TSH assay", "", 0.65)],
    # Cancer
    "C50": [CodeSuggestion("80100", "Chemotherapy cycle — breast cancer", "", 0.65),
            CodeSuggestion("07600", "Mammography", "", 0.55)],
    "C34": [CodeSuggestion("80200", "Chemotherapy cycle — lung cancer", "", 0.65)],
    "C61": [CodeSuggestion("55300", "Radical prostatectomy", "", 0.65)],
    # Mental health
    "F32": [CodeSuggestion("00120", "Psychiatrist consultation — depression", "", 0.65)],
    "F41": [CodeSuggestion("00120", "Psychiatrist consultation — anxiety", "", 0.65)],
    "F20": [CodeSuggestion("00250", "Psychiatric inpatient day rate", "", 0.60)],
    # Skin
    "L20": [CodeSuggestion("00110", "GP consultation — atopic dermatitis", "", 0.65)],
    "L40": [CodeSuggestion("00120", "Dermatologist consultation — psoriasis", "", 0.60)],
    # Eye / ENT
    "H26": [CodeSuggestion("66984", "Cataract extraction with IOL", "", 0.80)],
    "H66": [CodeSuggestion("00110", "GP consultation — otitis media", "", 0.65)],
    # Injuries / burns / poisoning
    "T14": [CodeSuggestion("00110", "GP consultation — minor injury", "", 0.55)],
    "T20": [CodeSuggestion("00250", "Burn unit inpatient day rate", "", 0.70)],
    # Reproductive
    "N92": [CodeSuggestion("00120", "Gynaecologist consultation", "", 0.60)],
    "D25": [CodeSuggestion("58150", "Myomectomy", "", 0.65),
            CodeSuggestion("58180", "Hysterectomy — abdominal", "", 0.60)],
}


@register(InsuranceLine.HEALTH)
def suggest(context: dict[str, Any]) -> list[CodeSuggestion]:
    icds = context.get("diagnosis_codes") or []
    seen: dict[str, CodeSuggestion] = {}
    for icd in icds:
        if not isinstance(icd, str):
            continue
        candidates: list[CodeSuggestion] = []
        candidates.extend(_HINTS.get(icd, []))
        if icd[:3] != icd:
            candidates.extend(_HINTS.get(icd[:3], []))
        for hint in candidates:
            prev = seen.get(hint.code)
            if prev is None or hint.confidence > prev.confidence:
                seen[hint.code] = hint
    return sorted(seen.values(), key=lambda s: s.confidence, reverse=True)
