#  Integrating CloudFS with Zovida

## Set up

### System Requirements

- Java 8 or higher
- Maven 3.5+
- Neo4J Should already be setup

The **LMS** app directory looks something like the tree below:

```
|-- Procfile
|-- package.json
|-- pom.xml
|-- b2intgr
|   |-- README.md
|   |-- bin
|   |-- pom.xml
|   `-- src
|-- app.js
|-- .env
|-- app.json
|-- jsdoc-conf.json
|-- node_modules
|-- scripts
|-- src
|-- test
`-- webpack.config.js
```

There are a few new files:

+ `b2intgr/`
    - Contains the BackBlaze integration
    
+ `pom.xml`
    - This is only here to let Heroku know that `maven` & `java` are required for this app
    - It is defined as the parent POM to `b2intrgr/pom.xml` and tricks Heroku into compiling the true target
    
+ `Procfile`
    - This is now **Heroku's** starting point. It starts both servers after building:
```
    web: b2intgr/bin/cloudfs.sh start && npm start
```

To run `heroku local`,  modify the `.env` file to set the following variables. Eg:
```
GRAPHENEDB_BOLT_PASSWORD=reggae
GRAPHENEDB_BOLT_USER=neo4j
GRAPHENEDB_BOLT_URL=bolt://localhost:7476
PORT=5555
```

The `PORT` value is for **expressJS**.
Both **CloudFS** and **expressJS** use the `GRAPHENE` settings.

Alternatively, one can modify the config files:

   [Main config](b2intgr/src/main/resources/config.json) - for the main app
    
   and [Test config](b2intgr/src/test/resources/config.json) - for test configuration.

(The **test** config contains instructions and comments)


## Deploy to Heroku

Assumptions:
1 directory initialized as git repo:
    `git init`

2. A Heroku app exists or was created:
    `heroku create`
----

Assuming nodejs buildpack already added:

- Add the Java buildpack:
```
    heroku buildpacks:add heroku/java
```

The java buildpack should come after `heroku/node`

- Confirm:

```
   $ heroku buildpacks
```
Output:
```
=== rdnpm Buildpack URLs
1. heroku/nodejs
2. heroku/java
```

- Add graphenedb:

    `heroku addons:create graphenedb:dev-free`

- Finally:
```
    git push heroku master
```

## Development and Testing

Set properties in the config.json file. `$VARIABLES` may be used directly in the config file. They will be interpolated from corresponding **environment** variables. That is, for BASH:
```
    export VARN=value
```

```json

    "poolSize": 5,
    "maxPoolSize": $VARN,
    "neo4jConf": {
        // Be sure to use the BOLT server's port
        "urlString": "$GRAPHENEDB_BOLT_URL",
        "username": "$GRAPHENEDB_BOLT_USER",
    ...,
    },

```
... Same as setting the values in `.env`.











