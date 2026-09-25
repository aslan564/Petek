package az.petek.oracle.testing

import az.petek.oracle.domain.OracleResponse
import az.petek.oracle.domain.OracleSafetyException
import az.petek.oracle.domain.SeedCompanyRequest
import az.petek.oracle.domain.SeedCompanyResult
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.domain.TestCompany
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Scriptable oracle: register JSON answers per path; records seeding and deletion calls. */
class FakeTargetOracle(
    override val isAvailable: Boolean = true,
) : TargetOracle {
    val responses = ConcurrentHashMap<String, OracleResponse>()
    val otps = ConcurrentHashMap<String, String>()
    val companies = ConcurrentHashMap<String, TestCompany>()
    val owners = ConcurrentHashMap<String, String>()
    val seeded = CopyOnWriteArrayList<SeedCompanyRequest>()
    val deleted = CopyOnWriteArrayList<String>()

    fun respond(
        path: String,
        json: String,
        status: Int = 200,
    ) {
        responses[path] = OracleResponse(status, Json.parseToJsonElement(json), json)
    }

    fun respond(
        path: String,
        body: JsonElement,
        status: Int = 200,
    ) {
        responses[path] = OracleResponse(status, body, body.toString())
    }

    override suspend fun get(path: String): OracleResponse = responses[path] ?: OracleResponse(404, null, "")

    override suspend fun latestOtp(phone: String): String? = otps[phone]

    override suspend fun companyByOwner(ownerEmail: String): TestCompany? = owners[ownerEmail]?.let { companies[it] }

    override suspend fun company(companyId: String): TestCompany? = companies[companyId]

    override suspend fun seedCompany(request: SeedCompanyRequest): SeedCompanyResult {
        seeded += request
        val company = companies[request.companyId]
        return SeedCompanyResult(
            companyId = request.companyId,
            companyCode = company?.code,
            departmentIds = request.departments.withIndex().associate { (i, d) -> d to "dep-${i + 1}" },
            inviteLinks = emptyMap(),
        )
    }

    override suspend fun deleteCompany(companyId: String) {
        val company = companies[companyId] ?: throw OracleSafetyException("Unknown company $companyId")
        if (!company.isTest) throw OracleSafetyException("Company $companyId is not a test company")
        deleted += companyId
        companies.remove(companyId)
    }
}
