# Embeddings Java SQLite

API REST em Spring Boot para armazenar mensagens e realizar busca semântica com embeddings locais. O projeto usa Spring Web, Spring JDBC, [LangChain4j](https://docs.langchain4j.dev/), o modelo multilíngue `paraphrase-multilingual-MiniLM-L12-v2` e SQLite.

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

O documento OpenAPI inclui operações para criação, listagem, exclusão, busca semântica por usuário, busca semântica global e reindexação dos embeddings. A geração dessa especificação também é validada pelos testes de integração.

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

Ao receber uma mensagem, a aplicação gera um vetor de embedding e o armazena como um BLOB no SQLite. Nas buscas, ela gera o embedding do texto consultado e ordena as mensagens pela similaridade de cosseno.

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
