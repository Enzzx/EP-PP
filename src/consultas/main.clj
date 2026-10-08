(ns consultas.main
  "Unico namespace com I/O e com doseq: leitura do arquivo e laco do
  terminal. O laco e escrito com loop/recur: le uma linha, responde e
  volta ao comeco. Nenhuma entrada derruba o programa."

  (:require [clojure.string :as str]
            [consultas.core :as core])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Formatacao de saida
;; ---------------------------------------------------------------------------

(defn- formatar-decimal [v]
  (String/format java.util.Locale/ROOT "%.2f" (to-array [(double v)])))

(defn- formatar-valor [v tipo]
  (if (= tipo :decimal)
    (formatar-decimal v)
    (str v)))

(defn- tipos-do-esquema [esquema]
  (into {} esquema))

(defn- campos-impressao [ast esquema]
  (if-let [selecionar (last (filter (fn [[tipo]] (= tipo :selecionar))
                                    (:estagios ast)))]
    (second selecionar)
    (mapv first esquema)))

(defn- imprimir-registros [registros ast esquema]
  (if (empty? registros)
    (println "(vazio)")
    (let [tipos (tipos-do-esquema esquema)
          campos (campos-impressao ast esquema)]
      (doseq [reg registros]
        (println (str/join "; " (map #(formatar-valor (get reg %) (get tipos %))
                                     campos)))))))

(defn- imprimir-grupos [grupos agrupar tipos]
  (let [[_ campo [tipo-agregacao & args]] agrupar
        tipo-chave (get tipos campo)
        tipo-valor (case tipo-agregacao
                     :contar :inteiro
                     :soma (get tipos (first args))
                     :media :decimal)]
    (doseq [{:keys [chave valor]} grupos]
      (println (str (formatar-valor chave tipo-chave)
                    "; "
                    (formatar-valor valor tipo-valor))))))

(defn- imprimir-resultado [resultado ast esquema]
  (cond
    (and (map? resultado) (contains? resultado :erros))
    (println (str "ERRO: " (first (:erros resultado))))

    (number? resultado)
    (let [[tipo-terminal campo] (:terminal ast)
          tipos (tipos-do-esquema esquema)]
      (println (case tipo-terminal
                 :contar (str resultado)
                 :soma (formatar-valor resultado (get tipos campo))
                 :media (formatar-decimal resultado))))

    (and (sequential? resultado)
         (every? #(and (map? %) (contains? % :chave)) resultado))
    (let [agrupar (last (filter (fn [[tipo]] (= tipo :agrupar-por))
                                (:estagios ast)))]
      (if (empty? resultado)
        (println "(vazio)")
        (imprimir-grupos resultado agrupar (tipos-do-esquema esquema))))

    :else
    (imprimir-registros resultado ast esquema)))

;; ---------------------------------------------------------------------------
;; Processamento de comandos
;; ---------------------------------------------------------------------------

(defn- responder-query [resto dados]
  (try
    (let [ast (core/analisar resto)
          resultado (core/executar ast dados)]
      (imprimir-resultado resultado ast (:esquema dados)))
    (catch Exception e
      (println (str "ERRO: " (.getMessage e))))))

(defn- responder [linha dados]
  (let [comando (str/trim linha)]
    (cond
      (str/blank? comando)
      (println "ERRO: linha em branco")

      (or (= comando "QUERY") (str/starts-with? comando "QUERY "))
      (let [resto (str/trim (subs comando 5))]
        (if (str/blank? resto)
          (imprimir-registros (:registros dados) {:estagios [] :terminal nil}
                              (:esquema dados))
          (responder-query resto dados)))

      :else
      (println (str "ERRO: comando desconhecido: " comando)))))

;; ---------------------------------------------------------------------------
;; Laco do terminal: loop/recur, sem estado mutavel
;; ---------------------------------------------------------------------------

(defn- laco [dados]
  (print "> ")
  (flush)
  (when-let [linha (read-line)]
    (when-not (= "EXIT" (str/trim linha))
      (responder linha dados)
      (recur dados))))

(defn -main [& args]
  (if (empty? args)
    (println "ERRO: informe o caminho de um arquivo CSV")
    (let [caminho (first args)]
      (try
        (let [dados (core/ler-csv (slurp caminho))]
          (laco dados))
        (catch Exception e
          (println (str "ERRO: " (.getMessage e))))))))
