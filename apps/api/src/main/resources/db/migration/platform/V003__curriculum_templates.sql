-- ============================================================================
-- Seed master curriculum templates (per §9 curriculum engine deep-dive).
-- Payload shape consumed by CurriculumRepository#cloneFromTemplate:
--   {"nodes": [{"type","code","name","children":[...],"learningOutcomes":[
--     {"code","statement","bloomLevel"}]}]}
-- node_type must be one of: strand, unit, chapter, topic, subtopic
-- (matches the CHECK constraint on chain_X.curriculum_node).
-- ============================================================================

INSERT INTO platform.curriculum_template (id, board_code, strategy_code, name, version, grade_band, payload)
VALUES (
  gen_random_uuid(), 'CIE', 'CIE-IGCSE', 'CIE IGCSE Mathematics 0580', '2026',
  'IGCSE',
  $${
    "nodes": [
      {
        "type": "strand", "code": "N", "name": "Number",
        "children": [
          {
            "type": "unit", "code": "N1", "name": "Integers, HCF, LCM",
            "children": [
              {
                "type": "topic", "code": "1.4", "name": "Prime factorisation",
                "children": [],
                "learningOutcomes": [
                  {"code": "LO 1.4.1", "statement": "Express a number as a product of prime factors", "bloomLevel": "apply"},
                  {"code": "LO 1.4.2", "statement": "Find HCF/LCM of two numbers by prime factorisation", "bloomLevel": "apply"}
                ]
              },
              {
                "type": "topic", "code": "1.5", "name": "Standard form",
                "children": [],
                "learningOutcomes": [
                  {"code": "LO 1.5.1", "statement": "Calculate with numbers in standard form", "bloomLevel": "apply"}
                ]
              }
            ]
          },
          {
            "type": "unit", "code": "N2", "name": "Ratio, proportion, rate",
            "children": [
              {
                "type": "topic", "code": "1.9", "name": "Direct and inverse proportion",
                "children": [],
                "learningOutcomes": [
                  {"code": "LO 1.9.1", "statement": "Set up and use equations for direct and inverse proportion", "bloomLevel": "apply"}
                ]
              }
            ]
          }
        ]
      },
      {
        "type": "strand", "code": "A", "name": "Algebra and graphs",
        "children": [
          {
            "type": "unit", "code": "A1", "name": "Algebraic representation and manipulation",
            "children": [
              {
                "type": "topic", "code": "2.2", "name": "Factorisation",
                "children": [],
                "learningOutcomes": [
                  {"code": "LO 2.2.1", "statement": "Factorise quadratic expressions", "bloomLevel": "apply"}
                ]
              }
            ]
          }
        ]
      },
      {
        "type": "strand", "code": "G", "name": "Geometry",
        "children": [
          {
            "type": "unit", "code": "G1", "name": "Geometrical terms and relationships",
            "children": [
              {
                "type": "topic", "code": "4.3", "name": "Angle properties of polygons",
                "children": [],
                "learningOutcomes": [
                  {"code": "LO 4.3.1", "statement": "Calculate interior and exterior angles of polygons", "bloomLevel": "understand"}
                ]
              }
            ]
          }
        ]
      }
    ]
  }$$::jsonb
) ON CONFLICT (board_code, strategy_code, name, version) DO NOTHING;

INSERT INTO platform.curriculum_template (id, board_code, strategy_code, name, version, grade_band, payload)
VALUES (
  gen_random_uuid(), 'CBSE', 'CBSE-CCE-2024', 'CBSE Class 10 Mathematics — NCERT 2024', '2024',
  '10',
  $${
    "nodes": [
      {
        "type": "chapter", "code": "Ch1", "name": "Real Numbers",
        "children": [
          {
            "type": "topic", "code": "1.1", "name": "Euclid's division lemma",
            "children": [],
            "learningOutcomes": [
              {"code": "LO-1.1.1", "statement": "State and apply Euclid's division lemma", "bloomLevel": "apply"},
              {"code": "LO-1.1.2", "statement": "Find HCF of two positive integers using Euclid's algorithm", "bloomLevel": "apply"}
            ]
          },
          {
            "type": "topic", "code": "1.2", "name": "Fundamental theorem of arithmetic",
            "children": [],
            "learningOutcomes": [
              {"code": "LO-1.2.1", "statement": "Express composite numbers as a product of primes uniquely", "bloomLevel": "understand"}
            ]
          }
        ]
      },
      {
        "type": "chapter", "code": "Ch2", "name": "Polynomials",
        "children": [
          {
            "type": "topic", "code": "2.1", "name": "Zeroes of a polynomial",
            "children": [],
            "learningOutcomes": [
              {"code": "LO-2.1.1", "statement": "Find zeroes of quadratic and cubic polynomials", "bloomLevel": "apply"}
            ]
          }
        ]
      },
      {
        "type": "chapter", "code": "Ch3", "name": "Pair of Linear Equations in Two Variables",
        "children": [
          {
            "type": "topic", "code": "3.1", "name": "Graphical method of solution",
            "children": [],
            "learningOutcomes": [
              {"code": "LO-3.1.1", "statement": "Solve a pair of linear equations graphically", "bloomLevel": "apply"}
            ]
          }
        ]
      }
    ]
  }$$::jsonb
) ON CONFLICT (board_code, strategy_code, name, version) DO NOTHING;
