/*
 * Copyright (c) 2021 PANTHEON.tech s.r.o. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.server.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import io.lighty.core.controller.impl.config.ConfigurationException;
import io.lighty.server.config.LightyServerConfig;
import io.lighty.server.config.SecurityConfig;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStore.Builder;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Optional;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LightyServerConfigUtils {

    private static final String SERVER_CONFIG_ROOT_ELEMENT_NAME = "lighty-server";
    private static final Logger LOG = LoggerFactory.getLogger(LightyServerConfigUtils.class);

    private LightyServerConfigUtils() {
        // Hide on purpose
    }

    public static LightyServerConfig getServerConfiguration(final InputStream jsonConfigIS)
            throws ConfigurationException {
        Preconditions.checkState(jsonConfigIS != null, "Provided JSON configuration is null");
        final var mapper = new ObjectMapper();
        final JsonNode configNode;
        try {
            configNode = mapper.readTree(jsonConfigIS);
        } catch (final IOException e) {
            throw new ConfigurationException("Cannot deserialize Json content to Json tree nodes", e);
        }
        if (!configNode.has(SERVER_CONFIG_ROOT_ELEMENT_NAME)) {
            LOG.warn("Json config does not contain {} element. Using defaults.", SERVER_CONFIG_ROOT_ELEMENT_NAME);
            return new LightyServerConfig();
        }
        final var lightyServerNode = configNode.path(SERVER_CONFIG_ROOT_ELEMENT_NAME);
        final LightyServerConfig lightyServerConfig;
        try {
            lightyServerConfig = mapper.treeToValue(lightyServerNode, LightyServerConfig.class);
            lightyServerConfig.setSecurityConfig(createSecurityConfig(lightyServerConfig));
        } catch (final JsonProcessingException e) {
            throw new ConfigurationException(String.format("Cannot bind Json tree to type: %s",
                    LightyServerConfig.class), e);
        }
        return lightyServerConfig;
    }

    public static LightyServerConfig getDefaultLightyServerConfig() throws ConfigurationException {
        final var lightyServerConfig = new LightyServerConfig();
        lightyServerConfig.setSecurityConfig(createSecurityConfig(lightyServerConfig));
        return lightyServerConfig;
    }

    public static SecurityConfig createSecurityConfig(final LightyServerConfig config) throws ConfigurationException {
        try {
            final var keystore = getKeyStore(isDefaultKeystoreConfig(config), config.getKeyStoreFilePath(),
                    config.getKeyStoreType(), config.getKeyStorePassword());
            LOG.info("Keystore successfully loaded");
            final var truststore = getKeyStore(isDefaultTrustedKeystoreConfig(config),
                    config.getTrustKeyStoreFilePath(), config.getKeyStoreType(), config.getTrustKeyStorePassword());
            LOG.info("Trust keystore successfully loaded");
            LOG.debug("Creating Security config from keystore [{}] and trustKeystore [{}]", keystore, truststore);
            return new SecurityConfig(keystore, config.getKeyStorePassword(), truststore,
                    config.getTrustKeyStorePassword(), config.isEnableSniHostCheck(), config.isNeedClientAuth());
        } catch (final IOException | NoSuchAlgorithmException | CertificateException | KeyStoreException
                 | NoSuchProviderException | OperatorCreationException e) {
            throw new ConfigurationException("Unable to create KeyStore configuration", e);
        }
    }

    private static KeyStore getKeyStore(final boolean isDefault, final String ksPath, final String ksType,
            final String ksPassword) throws CertificateException, KeyStoreException, IOException,
            NoSuchAlgorithmException, ConfigurationException, NoSuchProviderException, OperatorCreationException {
        if (isDefault)  {
            return getDefaultKeystore(ksPath, ksType, ksPassword);
        } else {
            return getCustomKeystore(ksPath, ksType, ksPassword);
        }
    }

    private static KeyStore getDefaultKeystore(final String ksPath, final String ksType, final String ksPassword)
            throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException,
            OperatorCreationException {
        final var resource = LightyServerConfigUtils.class.getClassLoader().getResource(ksPath);
        final var keystore = KeyStore.getInstance(ksType);
        if (resource != null) {
            // if exists, load
            LOG.info("Loading default keystore at path : {}", resource.getPath());
            try (FileInputStream fileInputStream = new FileInputStream(resource.getFile())) {
                keystore.load(fileInputStream, ksPassword.toCharArray());
            }
        } else {
            // if not exists, create
            final var path = LightyServerConfigUtils.class.getResource("/").getPath() + ksPath;
            LOG.info("Creating default keystore at path : {}", path);
            final var parent = Paths.get(path).getParent();
            if (!parent.toFile().exists()) {
                Files.createDirectories(parent);
            }
            final var keyPair = createKeyPair();
            final var certificate = getSelfSignCert(keyPair);
            keystore.load(null, ksPassword.toCharArray());
            keystore.setKeyEntry("certAlias", keyPair.getPrivate(), ksPassword.toCharArray(),
                    new X509Certificate[] { certificate });
            try (FileOutputStream fileOutputStream = new FileOutputStream(path)) {
                keystore.store(fileOutputStream, ksPassword.toCharArray());
            }
        }
        return keystore;
    }


    private static KeyPair createKeyPair() throws NoSuchAlgorithmException {
        final var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        return keyPairGenerator.genKeyPair();
    }

    private static X509Certificate getSelfSignCert(final KeyPair keyPair) throws CertificateException, IOException,
            OperatorCreationException {
        final var instant = Instant.now();
        final var certSerialNumber = new BigInteger(Long.toString(instant.getEpochSecond()));
        final var zdt = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
        final var notBeforeValid = GregorianCalendar.from(zdt);
        final var expiration = GregorianCalendar.from(zdt);
        // 1 Year validity
        expiration.add(Calendar.YEAR, 1);

        final var dnName = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, "localhost")
                .addRDN(BCStyle.OU, "PANTHEON.tech s.r.o")
                .build();

        final var certBuilder = new JcaX509v3CertificateBuilder(dnName, certSerialNumber, notBeforeValid.getTime(),
                expiration.getTime(), dnName, keyPair.getPublic());

        // https://www.encryptionconsulting.com/education-center/what-is-an-oid/
        // 2.5.29.32.0 identifier means “All Issuance Policies” and is a sort of wildcard policy.
        // Any policy will match this identifier during certificate chain validation.
        certBuilder.addExtension(new ASN1ObjectIdentifier("2.5.29.32.0"), true, new BasicConstraints(true));
        final var contentSigner = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
        return new JcaX509CertificateConverter()
                .setProvider(new BouncyCastleProvider())
                .getCertificate(certBuilder.build(contentSigner));
    }

    private static boolean isDefaultTrustedKeystoreConfig(final LightyServerConfig config) {
        final var defaultConfig = new LightyServerConfig();
        return config.getTrustKeyStoreFilePath().equals(defaultConfig.getTrustKeyStoreFilePath())
                && config.getTrustKeyStorePassword().equals(defaultConfig.getTrustKeyStorePassword())
                && config.getKeyStoreType().equals(defaultConfig.getKeyStoreType());
    }

    private static boolean isDefaultKeystoreConfig(final LightyServerConfig config) {
        final var defaultConfig = new LightyServerConfig();
        return config.getKeyStoreFilePath().equals(defaultConfig.getKeyStoreFilePath())
                && config.getKeyStorePassword().equals(defaultConfig.getKeyStorePassword())
                && config.getKeyStoreType().equals(defaultConfig.getKeyStoreType());
    }

    private static KeyStore getCustomKeystore(final String ksPath, final String ksType, final String ksPassword)
            throws ConfigurationException, IOException, KeyStoreException, CertificateException,
            NoSuchAlgorithmException {
        LOG.info("Loading custom keystore at path : {}", ksPath);
        final var passProtection = new PasswordProtection(ksPassword.toCharArray());
        final var keystore = Builder.newInstance(ksType, null, passProtection).getKeyStore();
        final var ksFile = readKeyStoreFile(ksPath);

        if (ksFile.isEmpty()) {
            throw new ConfigurationException("Unable to create KeyStore configuration: KeyStore file was not found"
                    + " on path: " + ksPath);
        }
        keystore.load(ksFile.get(), ksPassword.toCharArray());
        return keystore;
    }

    private static Optional<InputStream> readKeyStoreFile(final String keyStoreFilePath) throws IOException {
        final InputStream ksFile;
        final var path = Paths.get(keyStoreFilePath);
        LOG.info("Trying to load KeyStore from filesystem from path {}", path);
        if (path.toFile().exists()) {
            ksFile = Files.newInputStream(path);
        } else {
            LOG.info("KeyStore not found on filesystem, looking in resources on path {}", keyStoreFilePath);
            ksFile = LightyServerConfigUtils.class.getClassLoader().getResourceAsStream(keyStoreFilePath);
        }
        if (ksFile == null) {
            LOG.error("KeyStore was not found on path {} in filesystem or resources", keyStoreFilePath);
        }
        return Optional.ofNullable(ksFile);
    }
}
