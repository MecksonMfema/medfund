/**
 * ICD-10 chapter codes (WHO, 22 chapters). Used by the "Record death"
 * modal on the member detail page so causes-of-death are captured against
 * a controlled vocab that the MORTALITY_STUDY actuarial report can group on.
 *
 * The full ICD-10 catalogue is thousands of codes; the chapter-level split
 * is coarse enough to type at the front-desk and detailed enough to feed
 * per-cause A/E ratios (mortality studies rarely stratify below chapter).
 */
export interface Icd10Chapter {
  code: string;
  label: string;
}

export const ICD10_CHAPTERS: readonly Icd10Chapter[] = [
  { code: 'A00-B99', label: 'Certain infectious and parasitic diseases' },
  { code: 'C00-D48', label: 'Neoplasms' },
  { code: 'D50-D89', label: 'Diseases of the blood and blood-forming organs' },
  { code: 'E00-E90', label: 'Endocrine, nutritional, and metabolic diseases' },
  { code: 'F00-F99', label: 'Mental and behavioural disorders' },
  { code: 'G00-G99', label: 'Diseases of the nervous system' },
  { code: 'H00-H59', label: 'Diseases of the eye and adnexa' },
  { code: 'H60-H95', label: 'Diseases of the ear and mastoid process' },
  { code: 'I00-I99', label: 'Diseases of the circulatory system' },
  { code: 'J00-J99', label: 'Diseases of the respiratory system' },
  { code: 'K00-K93', label: 'Diseases of the digestive system' },
  { code: 'L00-L99', label: 'Diseases of the skin and subcutaneous tissue' },
  { code: 'M00-M99', label: 'Diseases of the musculoskeletal system and connective tissue' },
  { code: 'N00-N99', label: 'Diseases of the genitourinary system' },
  { code: 'O00-O99', label: 'Pregnancy, childbirth, and the puerperium' },
  { code: 'P00-P96', label: 'Certain conditions originating in the perinatal period' },
  { code: 'Q00-Q99', label: 'Congenital malformations, deformations, and chromosomal abnormalities' },
  { code: 'R00-R99', label: 'Symptoms, signs, and abnormal clinical/lab findings' },
  { code: 'S00-T98', label: 'Injury, poisoning, and external causes' },
  { code: 'V01-Y98', label: 'External causes of morbidity and mortality' },
  { code: 'Z00-Z99', label: 'Factors influencing health status and contact with services' },
  { code: 'U00-U85', label: 'Codes for special purposes' },
];
