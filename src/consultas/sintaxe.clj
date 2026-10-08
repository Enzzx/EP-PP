(ns consultas.sintaxe
  "Texto da consulta -> AST. Analise sintatica recursiva e sem estado:
  cada funcao recebe a sequencia de tokens e devolve o no reconhecido
  junto com os tokens que sobraram."

  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Erros de sintaxe: sinalizados com ex-info
;; ---------------------------------------------------------------------------

(defn- erro [msg]
  (throw (ex-info msg {:tipo :sintaxe})))

;; ---------------------------------------------------------------------------
;; Tokenizador
;; ---------------------------------------------------------------------------

(defn- palavra-char? [c]
  (or (Character/isLetter c)
      (Character/isDigit c)
      (= c \_)
      (= c \-)))

(defn- ler-texto [texto i]
  "Le a string entre aspas a partir de i (posicao apos o abre-aspas).
  Devolve [indice-do-fecha-aspas conteudo]."
  (let [n (count texto)]
    (loop [j i]
      (cond
        (>= j n) (erro "texto sem fechar aspas")
        (= (nth texto j) \") [j (subs texto i j)]
        :else (recur (inc j))))))

(defn- ler-numero [texto i]
  "Le um inteiro ou decimal a partir de i. Devolve [token indice-final]."
  (let [n (count texto)
        comeco (if (= (nth texto i) \-) (inc i) i)]
    (loop [j comeco
           decimal? false]
      (if (and (< j n) (Character/isDigit (nth texto j)))
        (recur (inc j) decimal?)
        (if (and (not decimal?) (< j n) (= (nth texto j) \.))
          (recur (inc j) true)
          (let [txt (subs texto i j)]
            (if decimal?
              [[:decimal (Double/parseDouble txt)] j]
              [[:inteiro (Long/parseLong txt)] j])))))))

(defn- ler-palavra [texto i]
  "Le um identificador a partir de i. Devolve [indice-final palavra]."
  (let [n (count texto)]
    (loop [j i]
      (if (and (< j n) (palavra-char? (nth texto j)))
        (recur (inc j))
        [j (subs texto i j)]))))

(defn tokenizar [texto]
  "Divide o texto da consulta em tokens, cada um um vetor dado:
  [:palavra s], [:inteiro n], [:decimal d], [:texto s], [:op operador],
  [:abre], [:fecha], [:barra], [:virgula]."
  (let [n (count texto)]
    (loop [i 0
           acc []]
      (if (>= i n)
        acc
        (let [c (nth texto i)
              prox (when (< (inc i) n) (nth texto (inc i)))]
          (cond
            (Character/isWhitespace c)
            (recur (inc i) acc)

            (= c \()
            (recur (inc i) (conj acc [:abre]))

            (= c \))
            (recur (inc i) (conj acc [:fecha]))

            (= c \|)
            (recur (inc i) (conj acc [:barra]))

            (= c \,)
            (recur (inc i) (conj acc [:virgula]))

            (= c \=)
            (recur (inc i) (conj acc [:op :=]))

            (= c \!)
            (if (= prox \=)
              (recur (+ i 2) (conj acc [:op :!=]))
              (erro (str "operador desconhecido: !")))

            (= c \<)
            (if (= prox \=)
              (recur (+ i 2) (conj acc [:op :<=]))
              (recur (inc i) (conj acc [:op :<])))

            (= c \>)
            (if (= prox \=)
              (recur (+ i 2) (conj acc [:op :>=]))
              (recur (inc i) (conj acc [:op :>])))

            (= c \")
            (let [[fim conteudo] (ler-texto texto (inc i))]
              (recur (inc fim) (conj acc [:texto conteudo])))

            (Character/isDigit c)
            (let [[token fim] (ler-numero texto i)]
              (recur fim (conj acc token)))

            (and (= c \-) prox (Character/isDigit prox))
            (let [[token fim] (ler-numero texto i)]
              (recur fim (conj acc token)))

            (or (Character/isLetter c) (= c \_))
            (let [[fim palavra] (ler-palavra texto i)]
              (recur fim (conj acc [:palavra palavra])))

            :else
            (erro (str "caracter inesperado: " c))))))))

;; ---------------------------------------------------------------------------
;; Analise sintatica: cada funcao devolve [no tokens-sobrados]
;; ---------------------------------------------------------------------------

(declare parse-expr)

(defn- esperar-palavra [tokens esperada]
  (let [[tipo valor] (first tokens)]
    (if (and (= tipo :palavra) (= valor esperada))
      (rest tokens)
      (erro (str "esperava \"" esperada "\"")))))

(defn- esperar-token [tipo tokens]
  (if (= tipo (first (first tokens)))
    (rest tokens)
    (erro (str "esperava " (name tipo)))))

(defn- parse-campo [tokens]
  (let [[tipo valor] (first tokens)]
    (if (= tipo :palavra)
      [(keyword valor) (rest tokens)]
      (erro "campo esperado"))))

(defn- parse-literal [tokens]
  (let [tok (first tokens)]
    (if (#{:inteiro :decimal :texto} (first tok))
      [(second tok) (rest tokens)]
      (erro "falta o literal"))))

(defn- parse-op [tokens]
  (let [tok (first tokens)]
    (if (= :op (first tok))
      [(second tok) (rest tokens)]
      (let [[tipo valor] tok]
        (if (= tipo :palavra)
          (erro (str "operador desconhecido: " valor))
          (erro "operador esperado"))))))

(defn- parse-primario [tokens]
  (let [[tipo] (first tokens)]
    (cond
      (= tipo :abre)
      (let [[no resto] (parse-expr (rest tokens))
            resto (esperar-token :fecha resto)]
        [no resto])

      (= tipo :palavra)
      (let [[campo resto] (parse-campo tokens)
            [op resto] (parse-op resto)
            [literal resto] (parse-literal resto)]
        [[op campo literal] resto])

      :else
      (erro "expressao esperada"))))

(defn- parse-nao [tokens]
  (let [[tipo valor] (first tokens)]
    (if (and (= tipo :palavra) (= valor "nao"))
      (let [[no resto] (parse-nao (rest tokens))]
        [[:nao no] resto])
      (parse-primario tokens))))

(defn- parse-e [tokens]
  (let [[no resto] (parse-nao tokens)]
    (loop [no no
           resto resto]
      (let [[tipo valor] (first resto)]
        (if (and (= tipo :palavra) (= valor "e"))
          (let [[dir resto] (parse-nao (rest resto))]
            (recur [:e no dir] resto))
          [no resto])))))

(defn- parse-ou [tokens]
  (let [[no resto] (parse-e tokens)]
    (loop [no no
           resto resto]
      (let [[tipo valor] (first resto)]
        (if (and (= tipo :palavra) (= valor "ou"))
          (let [[dir resto] (parse-e (rest resto))]
            (recur [:ou no dir] resto))
          [no resto])))))

(defn- parse-expr [tokens]
  (parse-ou tokens))

(defn- parse-campos [tokens]
  "campo [, campo]*"
  (let [[campo resto] (parse-campo tokens)]
    (loop [acc [campo]
           resto resto]
      (if (= :virgula (first (first resto)))
        (let [[campo resto] (parse-campo (rest resto))]
          (recur (conj acc campo) resto))
        [acc resto]))))

(defn- parse-agregacao [tokens]
  (let [[tipo valor] (first tokens)]
    (if (= tipo :palavra)
      (case valor
        "contar" [[:contar] (rest tokens)]
        "soma" (let [[campo resto] (parse-campo (rest tokens))]
                 [[:soma campo] resto])
        "media" (let [[campo resto] (parse-campo (rest tokens))]
                  [[:media campo] resto])
        (erro (str "agregacao desconhecida: " valor)))
      (erro "falta \"com <agregacao>\""))))

(defn- parse-elemento [tokens]
  (let [[tipo valor] (first tokens)]
    (when-not (= tipo :palavra)
      (erro "estagio esperado"))
    (case valor
      "onde" (let [[expr resto] (parse-expr (rest tokens))]
               [[:onde expr] resto])

      "ordenar" (let [resto (esperar-palavra (rest tokens) "por")
                      [campo resto] (parse-campo resto)
                      [tipo2 valor2] (first resto)]
                  (if (and (= tipo2 :palavra) (= valor2 "desc"))
                    [[:ordenar-por campo :desc] (rest resto)]
                    [[:ordenar-por campo :asc] resto]))

      "limitar" (let [[tipo2 valor2] (first (rest tokens))]
                  (if (= tipo2 :inteiro)
                    [[:limitar valor2] (rest (rest tokens))]
                    (erro "numero esperado")))

      "selecionar" (let [[campos resto] (parse-campos (rest tokens))]
                     [[:selecionar campos] resto])

      "contar" [[:contar] (rest tokens)]

      "soma" (let [[campo resto] (parse-campo (rest tokens))]
               [[:soma campo] resto])

      "media" (let [[campo resto] (parse-campo (rest tokens))]
                [[:media campo] resto])

      "agrupar" (let [resto (esperar-palavra (rest tokens) "por")
                      [campo resto] (parse-campo resto)
                      resto (if (= :palavra (first (first resto)))
                              (esperar-palavra resto "com")
                              (erro "falta \"com <agregacao>\""))
                      [agregacao resto] (parse-agregacao resto)]
                  [[:agrupar-por campo agregacao] resto])

      (erro (str "estagio desconhecido: " valor)))))

(defn- parse-elementos [tokens]
  (when (= :barra (first (first tokens)))
    (erro "estagio vazio"))
  (loop [toks tokens
         acc []]
    (let [[elemento resto] (parse-elemento toks)
          acc (conj acc elemento)]
      (if (= :barra (first (first resto)))
        (let [apos-barra (rest resto)]
          (when (empty? apos-barra)
            (erro "estagio vazio"))
          (recur apos-barra acc))
        (do
          (when (seq resto)
            (erro (str "token sobrando: " (pr-str (first resto)))))
          [acc])))))

;; ---------------------------------------------------------------------------
;; Montagem da AST: elemento final tem que ser o ultimo
;; ---------------------------------------------------------------------------

(def ^:private finais
  {:selecionar "selecionar tem que ser o ultimo"
   :agrupar-por "agrupar tem que ser o ultimo"
   :contar "o terminal tem que ser o ultimo"
   :soma "o terminal tem que ser o ultimo"
   :media "o terminal tem que ser o ultimo"})

(defn- eh-final? [[tipo]]
  (contains? finais tipo))

(defn- montar-ast [elementos]
  (let [indice-final (first (keep-indexed (fn [i elemento]
                                            (when (eh-final? elemento) i))
                                          elementos))]
    (cond
      (nil? indice-final)
      {:estagios (vec elementos) :terminal nil}

      (< indice-final (dec (count elementos)))
      (erro (finais (first (nth elementos indice-final))))

      :else
      (let [final (nth elementos indice-final)
            prefixo (vec (take indice-final elementos))]
        (if (#{:contar :soma :media} (first final))
          {:estagios prefixo :terminal final}
          {:estagios (conj prefixo final) :terminal nil})))))

;; ---------------------------------------------------------------------------
;; API
;; ---------------------------------------------------------------------------

(defn analisar
  "Transforma o texto de uma consulta (sem a palavra QUERY) em AST.
  Erro de sintaxe e sinalizado com ex-info."
  [texto]
  (let [tokens (tokenizar (or texto ""))]
    (if (empty? tokens)
      {:estagios [] :terminal nil}
      (let [[elementos] (parse-elementos tokens)]
        (montar-ast elementos)))))
