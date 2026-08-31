# Embeddings Java SQLite

API REST em Spring Boot para armazenar mensagens e realizar busca vetorial, lexical e híbrida. O projeto usa Spring Web, Spring JDBC, [LangChain4j](https://docs.langchain4j.dev/), o modelo multilíngue `paraphrase-multilingual-MiniLM-L12-v2`, SQLite FTS5, BM25 e Reciprocal Rank Fusion (RRF).

## Requisitos

- Java 17 ou mais recente
- O Gradle Wrapper incluído no projeto

## Como executar

```bash
./gradlew bootRun
```

A aplicação inicia em `http://localhost:8080`. O arquivo `user_messages.db` é criado automaticamente no diretório atual.

Na primeira execução, a aplicação baixa aproximadamente 135 MB do modelo ONNX quantizado e do tokenizer para `.models/`. Nas execuções seguintes, os arquivos são reutilizados e seus checksums são verificados.

## Swagger e OpenAPI

A documentação da API é gerada automaticamente pelo Springdoc a partir dos controllers, DTOs e validações do projeto. Com a aplicação em execução, acesse:

- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **OpenAPI JSON:** http://localhost:8080/v3/api-docs
- **OpenAPI YAML:** http://localhost:8080/v3/api-docs.yaml

Pelo Swagger UI é possível visualizar os endpoints, parâmetros, payloads, códigos de resposta e schemas. Também é possível executar chamadas reais contra a API:

1. Abra http://localhost:8080/swagger-ui.html.
2. Escolha um endpoint.
3. Clique em **Try it out**.
4. Preencha os parâmetros ou o corpo da requisição.
5. Clique em **Execute** para conferir o request, status HTTP e response.

O documento OpenAPI inclui operações para criação, listagem, exclusão, buscas vetorial, lexical e híbrida, além da reindexação dos embeddings. A geração dessa especificação também é validada pelos testes de integração.

## Endpoints

### Criar uma mensagem

```bash
curl -X POST http://localhost:8080/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"userId":"usuario-1","messageText":"Estou aprendendo Java"}'
```

### Listar mensagens de um usuário

```bash
curl 'http://localhost:8080/api/messages/user/usuario-1?limit=20'
```

### Buscar mensagens semelhantes de um usuário

```bash
curl -X POST 'http://localhost:8080/api/messages/user/usuario-1/search?limit=20' \
  -H 'Content-Type: text/plain' \
  --data 'programação em Java'
```

### Buscar mensagens semelhantes de todos os usuários

```bash
curl 'http://localhost:8080/api/messages/search?query=programa%C3%A7%C3%A3o%20em%20Java&limit=20'
```

### Busca lexical global com FTS5 e BM25

Indicada para palavras exatas, termos raros, siglas e códigos:

```bash
curl 'http://localhost:8080/api/messages/search/lexical?query=ERR-XPTO-409&limit=20'
```

### Busca lexical de um usuário

```bash
curl -X POST 'http://localhost:8080/api/messages/user/usuario-1/search/lexical?limit=20' \
  -H 'Content-Type: text/plain; charset=UTF-8' \
  --data 'ERR-XPTO-409'
```

### Busca híbrida global

Combina o ranking vetorial com o ranking FTS5/BM25 usando RRF:

```bash
curl 'http://localhost:8080/api/messages/search/hybrid?query=falha%20no%20pedido%20ERR-XPTO-409&limit=20'
```

### Busca híbrida de um usuário

```bash
curl -X POST 'http://localhost:8080/api/messages/user/usuario-1/search/hybrid?limit=20' \
  -H 'Content-Type: text/plain; charset=UTF-8' \
  --data 'falha no pedido ERR-XPTO-409'
```

### Excluir uma mensagem

```bash
curl -X DELETE http://localhost:8080/api/messages/ID_DA_MENSAGEM
```

### Reindexar mensagens antigas

Ao trocar o modelo de embeddings, recrie os vetores já armazenados:

```bash
curl -X POST http://localhost:8080/api/messages/reindex
```

Mensagens ainda não reindexadas continuam disponíveis na listagem, mas são ignoradas nas buscas semânticas para evitar a comparação de vetores produzidos por modelos diferentes.

O parâmetro opcional `limit` aceita valores entre 1 e 100 e usa 20 por padrão.

## Como funciona

Ao receber uma mensagem, a aplicação gera um vetor de embedding, armazena-o como BLOB e mantém o texto sincronizado em uma tabela virtual FTS5 por meio de triggers do SQLite.

Existem três estratégias de recuperação:

- **Vetorial:** compara os embeddings pela similaridade de cosseno; quanto maior o `similarityScore`, melhor.
- **Lexical:** usa SQLite FTS5 e BM25 para correspondências textuais; no valor bruto retornado pelo SQLite, scores BM25 menores são melhores. O campo `lexicalRank` torna a ordem explícita.
- **Híbrida:** combina as posições dos dois rankings com `1 / (60 + rank)` para cada fonte. Quanto maior o `rrfScore`, melhor. Os campos `vectorRank` e `lexicalRank` mostram como cada fonte contribuiu.

O RRF usa posições, e não tenta somar diretamente similaridade de cosseno e BM25, pois essas métricas possuem escalas diferentes.

As respostas expõem `vectorDimensions` para informar o tamanho do embedding sem transferir o vetor completo. O modelo atual produz vetores com 384 dimensões.

Todo o processamento de embeddings acontece localmente, sem necessidade de chave de API.

## Testes e build

```bash
./gradlew test
./gradlew integrationTest
./gradlew bootJar
java -jar build/libs/embeddings-java-sqlite-0.0.1-SNAPSHOT.jar
```

Os testes de integração sobem a aplicação em uma porta aleatória, usam um banco SQLite temporário e fazem requisições HTTP reais com o modelo multilíngue carregado. O comando `./gradlew check` executa tanto os testes unitários quanto os testes de integração.

Durante os testes de integração, o terminal mostra cada request e response HTTP, incluindo método, URL, payload, status e duração. Ao final de cada caso, o Gradle informa `PASSED`, `FAILED` ou `SKIPPED`.

### Relatório Allure

Para executar os testes e gerar um relatório HTML navegável:

```bash
./gradlew allureReport --depends-on-tests
```

Para gerar e abrir o relatório no navegador:

```bash
./gradlew allureServe
```

O relatório contém o status e a duração de cada teste, histórico de falhas e anexos com os requests e responses HTTP completos.
