/* =========================================================
   CARBON CREDIT TRADING SYSTEM
   Frontend Demo JavaScript
   ========================================================= */


/* =========================================================
   DEMO COMPANY DATA
   ========================================================= */

const DEMO_COMPANIES = [

    {
        id: "C001",
        name: "GreenSteel Pvt Ltd",
        domain: "MANUFACTURING",
        employees: 420,
        credits: 820,
        currentEmission: 720,
        limit: 1050,
        score: 0.731,
        status: "COMPLIANT"
    },

    {
        id: "C002",
        name: "SunPower Energy",
        domain: "ENERGY",
        employees: 280,
        credits: 510,
        currentEmission: 680,
        limit: 840,
        score: 0.557,
        status: "WARNING"
    },

    {
        id: "C003",
        name: "EcoMove Logistics",
        domain: "TRANSPORT",
        employees: 190,
        credits: 390,
        currentEmission: 260,
        limit: 342,
        score: 0.981,
        status: "COMPLIANT"
    },

    {
        id: "C004",
        name: "CloudLeaf IT",
        domain: "IT",
        employees: 650,
        credits: 270,
        currentEmission: 190,
        limit: 390,
        score: 0.641,
        status: "COMPLIANT"
    }

];


/* =========================================================
   DEMO TRADE DATA
   ========================================================= */

const DEMO_TRADES = [

    {
        id: "TX-0001",
        seller: "C003",
        buyer: "C001",
        credits: 80,
        price: 12.40,
        role: "BOUGHT",
        date: "Today"
    },

    {
        id: "TX-0002",
        seller: "C001",
        buyer: "C002",
        credits: 50,
        price: 14.10,
        role: "SOLD",
        date: "Yesterday"
    }

];


/* =========================================================
   GENERAL HELPERS
   ========================================================= */

function qs(selector) {

    return document.querySelector(selector);

}


/* =========================================================
   USER LOGIN
   ========================================================= */

function setUser(id, name = "Demo Company") {

    localStorage.setItem(
        "cc_user",
        JSON.stringify({
            id: id,
            name: name
        })
    );

}


function getUser() {

    try {

        return JSON.parse(
            localStorage.getItem("cc_user")
        );

    } catch (e) {

        return null;

    }

}


/* =========================================================
   LOGOUT
   ========================================================= */

function logout() {

    localStorage.removeItem("cc_user");

    location.href = "../index.html";

}


/* =========================================================
   LOGIN CHECK
   ========================================================= */

function requireLogin() {

    if (!getUser()) {

        location.href = "../login.html";

    }

}


/* =========================================================
   DISPLAY USER
   ========================================================= */

function fillUser() {

    const user = getUser();

    document
        .querySelectorAll("[data-user]")
        .forEach(element => {

            element.textContent =
                user ? user.name : "Company";

        });

}


/* =========================================================
   COMPANY DATA
   ========================================================= */

function companies() {

    return DEMO_COMPANIES;

}


/* =========================================================
   MONEY FORMAT
   ========================================================= */

function money(number) {

    return "Rs. " + Number(number).toFixed(2);

}


/* =========================================================
   NOTIFICATION
   ========================================================= */

function notify(message) {

    alert(message);

}


/* =========================================================
   BLOCKED COMPANY STORAGE
   =========================================================

   We store blocked company IDs in localStorage.

   Example:

   ["C002", "C004"]

   This means C002 and C004 are blocked.
   ========================================================= */

function getBlockedCompanies() {

    try {

        const blocked =
            JSON.parse(
                localStorage.getItem(
                    "cc_blocked_companies"
                )
            );

        return Array.isArray(blocked)
            ? blocked
            : [];

    } catch (e) {

        return [];

    }

}


/* =========================================================
   SAVE BLOCKED COMPANIES
   ========================================================= */

function saveBlockedCompanies(blockedIds) {

    localStorage.setItem(
        "cc_blocked_companies",
        JSON.stringify(blockedIds)
    );

}


/* =========================================================
   CHECK WHETHER COMPANY IS BLOCKED
   ========================================================= */

function isCompanyBlocked(companyId) {

    const blocked =
        getBlockedCompanies();

    return blocked.includes(companyId);

}


/* =========================================================
   BLOCK COMPANY
   ========================================================= */

function blockCompany(companyId) {

    let blocked =
        getBlockedCompanies();


    /* Already blocked */

    if (blocked.includes(companyId)) {

        notify(
            "This company is already blocked."
        );

        return;

    }


    /* Add company */

    blocked.push(companyId);


    /* Save */

    saveBlockedCompanies(blocked);


    /* Refresh page */

    renderCompanies();

    renderBlockedCompanies();


    notify(
        "Company " +
        companyId +
        " has been blocked successfully."
    );

}


/* =========================================================
   UNBLOCK COMPANY
   ========================================================= */

function unblockCompany(companyId) {

    let blocked =
        getBlockedCompanies();


    blocked =
        blocked.filter(
            id => id !== companyId
        );


    saveBlockedCompanies(blocked);


    /* Refresh page */

    renderCompanies();

    renderBlockedCompanies();


    notify(
        "Company " +
        companyId +
        " has been unblocked successfully."
    );

}


/* =========================================================
   STATUS BADGE
   ========================================================= */

function statusBadge(company) {

    /* BLOCKED STATUS */

    if (
        isCompanyBlocked(company.id)
    ) {

        return `
            <span class="badge warn">
                BLOCKED
            </span>
        `;

    }


    /* WARNING STATUS */

    if (
        company.status === "WARNING"
    ) {

        return `
            <span class="badge warn">
                WARNING
            </span>
        `;

    }


    /* NORMAL STATUS */

    return `
        <span class="badge">
            COMPLIANT
        </span>
    `;

}


/* =========================================================
   ACTION BUTTON
   ========================================================= */

function actionButton(company) {

    /* If blocked → show UNBLOCK */

    if (
        isCompanyBlocked(company.id)
    ) {

        return `
            <button
                class="btn small"
                onclick="unblockCompany('${company.id}')">

                Unblock

            </button>
        `;

    }


    /* Otherwise → show BLOCK */

    return `
        <button
            class="btn small secondary"
            onclick="blockCompany('${company.id}')">

            Block

        </button>
    `;

}


/* =========================================================
   RENDER ALL COMPANIES
   ========================================================= */

function renderCompanies() {

    const tableBody =
        qs("#companyRows");


    /* If this page doesn't have
       companyRows, stop. */

    if (!tableBody) {

        return;

    }


    tableBody.innerHTML = "";


    companies().forEach(company => {

        const row =
            document.createElement("tr");


        row.innerHTML = `

            <td>
                ${company.id}
            </td>

            <td>
                ${company.name}
            </td>

            <td>
                ${company.domain}
            </td>

            <td>
                ${Number(company.credits).toFixed(2)}
            </td>

            <td>
                ${company.currentEmission}
                /
                ${company.limit} t
            </td>

            <td>
                ${statusBadge(company)}
            </td>

            <td>
                ${actionButton(company)}
            </td>

        `;


        tableBody.appendChild(row);

    });

}


/* =========================================================
   RENDER BLOCKED COMPANIES
   ========================================================= */

function renderBlockedCompanies() {

    const tableBody =
        qs("#blockedCompanyRows");


    /* If blocked page isn't open,
       stop. */

    if (!tableBody) {

        return;

    }


    tableBody.innerHTML = "";


    const blockedIds =
        getBlockedCompanies();


    const blockedCompanies =
        companies().filter(
            company =>
                blockedIds.includes(
                    company.id
                )
        );


    /* No blocked companies */

    if (
        blockedCompanies.length === 0
    ) {

        const emptyMessage =
            qs("#noBlockedCompanies");


        if (emptyMessage) {

            emptyMessage.style.display =
                "block";

        }


        return;

    }


    /* Hide empty message */

    const emptyMessage =
        qs("#noBlockedCompanies");


    if (emptyMessage) {

        emptyMessage.style.display =
            "none";

    }


    /* Add blocked companies */

    blockedCompanies.forEach(company => {

        const row =
            document.createElement("tr");


        row.innerHTML = `

            <td>
                ${company.id}
            </td>

            <td>
                ${company.name}
            </td>

            <td>
                ${company.domain}
            </td>

            <td>
                ${Number(company.credits).toFixed(2)}
            </td>

            <td>
                ${company.currentEmission}
                /
                ${company.limit} t
            </td>

            <td>

                <span class="badge warn">
                    BLOCKED
                </span>

            </td>

            <td>

                <button
                    class="btn small"
                    onclick="unblockCompany('${company.id}')">

                    Unblock

                </button>

            </td>

        `;


        tableBody.appendChild(row);

    });

}


/* =========================================================
   REGISTER COMPANY
   ========================================================= */

function setupRegistration() {

    const registerForm =
        qs("#registerForm");


    if (!registerForm) {

        return;

    }


    registerForm.addEventListener(
        "submit",
        event => {

            event.preventDefault();


            const data =
                new FormData(
                    registerForm
                );


            const companyId =
                "C" +
                String(
                    Math.floor(
                        100 +
                        Math.random() *
                        899
                    )
                );


            localStorage.setItem(

                "cc_registered",

                JSON.stringify({

                    id: companyId,

                    name:
                        data.get("name"),

                    domain:
                        data.get("domain")

                })

            );


            const success =
                qs("#success");


            if (success) {

                success.innerHTML = `

                    Registered successfully.

                    Your demo Company ID is

                    <strong>
                        ${companyId}
                    </strong>.

                `;

                success.style.display =
                    "block";

            }

        }
    );

}


/* =========================================================
   COMPANY LOGIN
   ========================================================= */

function setupCompanyLogin() {

    const loginForm =
        qs("#loginForm");


    if (!loginForm) {

        return;

    }


    loginForm.addEventListener(
        "submit",
        event => {

            event.preventDefault();


            const id =
                qs("#companyId")
                    .value
                    .trim()
                    .toUpperCase();


            const found =
                companies().find(
                    company =>
                        company.id === id
                );


            /* Check blocked company */

            if (
                found &&
                isCompanyBlocked(found.id)
            ) {

                notify(
                    "Login denied. This company is blocked by the administrator."
                );

                return;

            }


            setUser(
                id,
                found
                    ? found.name
                    : "Demo Company"
            );


            location.href =
                "company/dashboard.html";

        }
    );

}


/* =========================================================
   EMISSION CALCULATOR
   ========================================================= */

function setupEmission() {

    const emissionForm =
        qs("#emissionForm");


    if (!emissionForm) {

        return;

    }


    emissionForm.addEventListener(
        "submit",
        event => {

            event.preventDefault();


            const electricity =
                +qs("#electricity").value ||
                0;


            const fuel =
                +qs("#fuel").value ||
                0;


            const production =
                +qs("#production").value ||
                0;


            const multiplier =
                +qs("#multiplier").value ||
                1;


            const kg =
                electricity * 0.82 +
                fuel * 2.68 +
                production *
                multiplier *
                5;


            qs("#emissionResult")
                .textContent =
                (
                    kg / 1000
                ).toFixed(2) +
                " tonnes CO₂";

        }
    );

}


/* =========================================================
   TRADE FORM
   ========================================================= */

function setupTrade() {

    const tradeForm =
        qs("#tradeForm");


    if (!tradeForm) {

        return;

    }


    tradeForm.addEventListener(
        "submit",
        event => {

            event.preventDefault();


            const amount =
                +qs("#amount").value ||
                0;


            if (amount <= 0) {

                notify(
                    "Enter a positive credit amount."
                );

                return;

            }


            qs("#tradeResult")
                .innerHTML = `

                    <span class="badge">
                        Demo trade request created
                    </span>

                    ${amount.toFixed(2)}
                    credits.

                `;

        }
    );

}


/* =========================================================
   COMPANY SEARCH
   ========================================================= */

function setupCompanySearch() {

    const search =
        qs("#companySearch");


    if (!search) {

        return;

    }


    search.addEventListener(
        "input",
        () => {

            const term =
                search.value
                    .toLowerCase();


            document
                .querySelectorAll(
                    "#companyRows tr"
                )
                .forEach(row => {

                    row.style.display =
                        row.innerText
                            .toLowerCase()
                            .includes(term)
                            ? ""
                            : "none";

                });

        }
    );

}


/* =========================================================
   INITIALIZE APPLICATION
   ========================================================= */

document.addEventListener(
    "DOMContentLoaded",
    () => {


        /* User */

        fillUser();


        /* Logout buttons */

        document
            .querySelectorAll(
                "[data-logout]"
            )
            .forEach(button => {

                button.addEventListener(
                    "click",
                    logout
                );

            });


        /* Registration */

        setupRegistration();


        /* Company login */

        setupCompanyLogin();


        /* Emission */

        setupEmission();


        /* Trade */

        setupTrade();


        /* Search */

        setupCompanySearch();


        /* Admin company page */

        renderCompanies();


        /* Blocked company page */

        renderBlockedCompanies();

    }
);